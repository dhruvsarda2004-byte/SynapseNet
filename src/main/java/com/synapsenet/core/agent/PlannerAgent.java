package com.synapsenet.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.core.executor.TestResults;
import com.synapsenet.core.planner.PlannerOutput;
import com.synapsenet.core.state.RepairAttempt;
import com.synapsenet.core.state.RepairPhase;
import com.synapsenet.core.state.RootCauseAnalysis;
import com.synapsenet.core.state.SharedState;
import com.synapsenet.llm.LLMClient;

import java.util.List;

@Component
public class PlannerAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    private final LLMClient llmClient;

    public PlannerAgent(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String getAgentId() {
        return "planner-agent-1";
    }

    @Override
    public AgentType getAgentType() {
        return AgentType.PLANNER;
    }

    public PlannerOutput generatePlan(String goal, SharedState state) {

        RepairPhase phase = state.getCurrentPhase();
        log.info("[Planner] Generating plan for phase: {}", phase);

        String prompt = buildPhaseAwarePlanPrompt(goal, state);
        log.info("[LLM] Planner prompt length: {}", prompt.length());

        String response = llmClient.generateWithRole(
                AgentType.PLANNER,
                prompt,
                llmClient.getTemperatureForRole(AgentType.PLANNER)
        );

        PlannerOutput plan = PlannerOutput.fromJson(goal, response);

        if (plan.getStepCount() == 0) {
            log.warn("[Planner] Empty plan generated. Injecting fallback for phase: {}", phase);
            return createFallbackPlan(goal, state, phase);
        }

        return plan;
    }

    public PlannerOutput revisePlan(String goal, SharedState state) {

        log.info("[Planner] Revising repair strategy");

        String failingFileAtReplan = state.getFailingArtifact();
        log.info("[Signal] failing_artifact at replanning: {}", failingFileAtReplan);

        if (failingFileAtReplan == null) {
            log.warn("[Planner] failing_artifact is null at replan — revised plan may use fallback steps.");
        }

        String prompt = buildReplanPrompt(goal, state);

        String response = llmClient.generateWithRole(
                AgentType.PLANNER,
                prompt,
                llmClient.getTemperatureForRole(AgentType.PLANNER)
        );

        PlannerOutput plan = PlannerOutput.fromJson(goal, response);

        if (plan.getStepCount() == 0) {
            log.warn("[Planner] Empty revised plan generated. Injecting fallback.");
            return createFallbackPlan(goal, state, RepairPhase.REPRODUCE);
        }

        return plan;
    }

    // ========================================================================
    // PHASE-AWARE PROMPTING
    // ========================================================================

    private String buildPhaseAwarePlanPrompt(String goal, SharedState state) {

        RepairPhase phase = state.getCurrentPhase();
        String failureContext = buildFailureContext(state);
        String failingFile = state.getFailingArtifact();

        log.info("[Signal] failing_artifact at planning: {}", failingFile);

        switch (phase) {
            case REPRODUCE:
                return buildReproducePrompt(goal, failureContext, state);
            case REPAIR_ANALYZE:
                return buildRepairAnalyzePrompt(goal, failureContext, state);
            case REPAIR_PATCH:
                return buildRepairPatchPrompt(goal, failureContext, failingFile, state);
            case VALIDATE:
                return buildValidatePrompt(goal);
            default:
                log.error("[Planner] Unknown phase: {}", phase);
                return buildRepairPatchPrompt(goal, failureContext, failingFile, state);
        }
    }

    private String buildReproducePrompt(String goal, String failureContext, SharedState state) {

        boolean needsDiscovery = !state.isStructureDiscovered();

        String exampleSteps = needsDiscovery ? """
      "repair_steps": [
        "Step 1: Use file_tree to discover project structure",
        "Step 2: Read test files to understand failure",
        "Step 3: Run tests to confirm failure"
      ]""" : """
      "repair_steps": [
        "Step 1: Read test files to understand failure",
        "Step 2: Run tests to confirm failure"
      ]""";

        return """
    You are in REPRODUCE phase.

    Your ONLY objectives:
    1. Discover project structure (if not done)
    2. Read test files
    3. Run tests to confirm failure

    ⚠️ CRITICAL RULES FOR REPRODUCE PHASE:
    - You MUST call file_tree FIRST if structure is unknown
    - Do NOT modify any files
    - Do NOT attempt repairs
    - Your final step MUST be running tests

    Issue:
    %s

    %s

    Structure discovered: %s

    STRICT OUTPUT FORMAT:
    {
    %s,
      "reasoning": "Discovery and test execution strategy"
    }

    Output ONLY valid JSON.
    """.formatted(goal, failureContext, state.isStructureDiscovered(), exampleSteps);
    }

    /**
     * REPAIR_ANALYZE phase prompt.
     *
     * Fix B: Do NOT anchor the task to a specific file or line number.
     * The Executor injects raw pytest failure output and file content.
     * The LLM must determine the root cause artifact from the evidence,
     * not from the task string.
     *
     * Previous implementation injected "Analyze the failure in rewrite.py near line 300"
     * which contradicted the raw exception output pointing to config/__init__.py.
     * The model correctly trusted the evidence but failed artifact path validation,
     * creating an unresolvable loop.
     *
     * The Executor already states the analyzer-identified artifact in the prompt body
     * as context — it is NOT the authoritative diagnostic anchor here.
     */
    private String buildRepairAnalyzePrompt(String goal, String failureContext, SharedState state) {

        return """
    You are in REPAIR_ANALYZE phase.

    Your ONLY objective: Plan a diagnosis task.
    The Executor will perform root-cause analysis — no tools, no patches yet.
    The Executor determines the root cause file from failure evidence directly.

    Issue:
    %s

    %s

    STRICT OUTPUT FORMAT:
    {
      "repair_steps": [
        "Analyze the root cause of the test failure and produce a structured JSON diagnosis identifying the exact bug site, causal chain, and minimal fix strategy."
      ],
      "reasoning": "Diagnosis before mutation — identify root cause from failure evidence before applying any patch"
    }

    Output ONLY valid JSON.
    """.formatted(goal, failureContext);
    }

    /**
     * REPAIR_PATCH phase prompt.
     * Injects lastRootCauseAnalysis to ground the patch task.
     *
     * CRITICAL ARCHITECTURE CONSTRAINT:
     * The Mediator requires hasPatch==true on EVERY task in REPAIR_PATCH phase.
     * Always generate exactly ONE task that patches in the same LLM response.
     *
     * Fix B corollary: The repair target here comes from the validated
     * RootCauseAnalysis.artifactPath (what the LLM diagnosed), NOT from the
     * PytestOutputAnalyzer's heuristic artifact. If analysis is valid, use its
     * artifact. Fallback to analyzer artifact only if no valid analysis exists.
     */
    private String buildRepairPatchPrompt(String goal, String failureContext,
                                          String failingFile, SharedState state) {

        // Prefer the diagnosed artifact from RootCauseAnalysis over analyzer heuristic.
        // The analysis artifact is what the LLM actually diagnosed as the bug site.
        String candidateFile;
        RootCauseAnalysis analysis = state.getLastRootCauseAnalysis();
        if (analysis != null && analysis.isValid() && analysis.getArtifactPath() != null) {
            candidateFile = analysis.getArtifactPath();
            log.info("[Planner] REPAIR_PATCH: using diagnosed artifact from analysis: {}", candidateFile);
        } else {
            candidateFile = resolveRepairTarget(failingFile, state);
            log.info("[Planner] REPAIR_PATCH: no valid analysis, using analyzer artifact: {}", candidateFile);
        }

        String analysisBlock = "";
        if (analysis != null && analysis.isValid()) {
            analysisBlock = "\n" + analysis.toRepairPatchPromptBlock() + "\n";
        }

        String exampleSteps;
        String finalReminders;

        if (candidateFile != null) {
            exampleSteps = """
          "repair_steps": [
            "Read %s and apply the fix described in the Root Cause Analysis using replace_in_file. The patch MUST implement the minimalFixStrategy. You MUST call replace_in_file in this same response."
          ]""".formatted(candidateFile);

            finalReminders = """
    FINAL REMINDERS:
    - Single task ONLY: Read AND apply replace_in_file in one response
    - Target file: %s
    - Patch MUST implement the Root Cause Analysis minimalFixStrategy above
    - NO tasks about testing
    - Read-only response will be REJECTED — you must apply a patch
    """.formatted(candidateFile);
        } else {
            exampleSteps = """
          "repair_steps": [
            "Explore src/ with list_files to find the source file, read it, and apply the fix described in the Root Cause Analysis using replace_in_file. You MUST call replace_in_file before finishing."
          ]""";

            finalReminders = """
    FINAL REMINDERS:
    - Single task ONLY: Explore, read, AND apply replace_in_file in one response
    - Patch MUST implement the Root Cause Analysis minimalFixStrategy above
    - NO tasks about testing
    - Read-only response will be REJECTED — you must apply a patch
    """;
        }

        return """
    You are in REPAIR_PATCH phase.

    Diagnosis is complete. Apply the fix now.

    Issue:
    %s

    %s
    %s

    STRICT OUTPUT FORMAT (copy exactly):
    {
    %s,
      "reasoning": "Apply the fix identified in the root cause analysis"
    }

    %s

    Output ONLY valid JSON.
    """.formatted(
                goal,
                failureContext,
                analysisBlock,
                exampleSteps,
                finalReminders
        );
    }

    private String resolveRepairTarget(String failingArtifact, SharedState state) {

        if (failingArtifact == null) {
            log.warn("[Planner] No failing artifact in state — cannot resolve repair target");
            return null;
        }

        boolean isSourceFile = failingArtifact.startsWith("src/") ||
                               failingArtifact.startsWith("_pytest/") ||
                               (!failingArtifact.startsWith("testing/") &&
                                !failingArtifact.startsWith("tests/"));

        if (isSourceFile) {
            log.info("[Planner] Artifact is source file — using directly: {}", failingArtifact);
            return failingArtifact;
        }

        log.info("[Planner] Artifact is test file — attempting generic source derivation: {}", failingArtifact);
        String derived = deriveSourceFromTestPath(failingArtifact);

        if (derived != null) {
            log.info("[Planner] Derived source target: {}", derived);
        } else {
            log.warn("[Planner] Could not derive source from test path: {} — will use discovery fallback",
                    failingArtifact);
        }

        return derived;
    }

    private String deriveSourceFromTestPath(String testArtifact) {

        String testFile = testArtifact.contains("::") ? testArtifact.split("::")[0] : testArtifact;

        if (testFile.startsWith("testing/test_")) {
            String moduleName = testFile
                    .replace("testing/test_", "")
                    .replace(".py", "");
            return "src/_pytest/" + moduleName + ".py";
        }

        if (testFile.startsWith("tests/test_")) {
            String moduleName = testFile
                    .replace("tests/test_", "")
                    .replace(".py", "");
            return "src/" + moduleName + ".py";
        }

        return null;
    }

    private String buildValidatePrompt(String goal) {
        return """
    You are in VALIDATE phase.

    Your ONLY objective: Run tests to verify the fix.

    ⚠️ CRITICAL RULES FOR VALIDATE PHASE:
    - ONLY run tests
    - Do NOT modify any files
    - Do NOT read files

    STRICT OUTPUT FORMAT:
    {
      "repair_steps": [
        "Step 1: Run tests to verify the fix"
      ],
      "reasoning": "Validation only"
    }

    Output ONLY valid JSON.
    """;
    }

    // ========================================================================
    // REPLAN PROMPT
    // ========================================================================

    private String buildReplanPrompt(String goal, SharedState state) {

        String failureContext     = buildFailureContext(state);
        String repairHistoryBlock = buildRepairHistorySection(state);
        String priorAnalysisBlock = buildPriorAnalysisSection(state);

        return """
Previous repair attempt failed.

Issue:
%s

%s

%s

%s

Create a NEW repair strategy that avoids ALL previously failed approaches above.

⚠️ CRITICAL: You are now in REPRODUCE phase after REPLAN.
Your first task MUST be running tests again to confirm failure.

STRICT OUTPUT FORMAT:
{
  "repair_steps": [
    "Step 1: Run tests to confirm failure still exists",
    "Step 2: Read relevant source file differently",
    "Step 3: Apply different fix approach"
  ],
  "reasoning": "Why this new strategy should succeed where previous ones failed"
}

RULES:
- MUST start with running tests (you are in REPRODUCE after REPLAN)
- Use EXACT file names from failure context
- Do NOT repeat any approach listed in repair history above
- Maximum 4 steps

Output ONLY valid JSON.
""".formatted(goal, failureContext, priorAnalysisBlock, repairHistoryBlock);
    }

    private String buildPriorAnalysisSection(SharedState state) {
        RootCauseAnalysis prior = state.getLastRootCauseAnalysis();
        if (prior == null) return "";

        return "=== PRIOR ROOT CAUSE ANALYSIS (led to failed patch — re-evaluate) ===\n"
               + prior.toReplanPromptBlock("FAILED")
               + "=== END PRIOR ANALYSIS ===\n";
    }

    private String buildRepairHistorySection(SharedState state) {
        List<RepairAttempt> history = state.getRepairHistory();
        if (history.isEmpty()) {
            return "=== REPAIR HISTORY ===\nNo prior repair attempts recorded.\n=== END REPAIR HISTORY ===";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== REPAIR HISTORY (").append(history.size()).append(" attempt(s)) ===\n\n");
        for (RepairAttempt attempt : history) {
            sb.append(attempt.toPromptSection()).append("\n");
        }
        sb.append("=== END REPAIR HISTORY ===");
        return sb.toString();
    }

    // ========================================================================
    // FALLBACK PLANS
    // ========================================================================

    private PlannerOutput createFallbackPlan(String goal, SharedState state, RepairPhase phase) {

        switch (phase) {
            case REPRODUCE: {
                String fallbackJson = """
                    {
                      "repair_steps": [
                        "Step 1: Use file_tree to discover project structure",
                        "Step 2: Run tests to reproduce failure"
                      ],
                      "reasoning": "Fallback: Basic reproduction workflow"
                    }
                    """;
                return PlannerOutput.fromJson(goal, fallbackJson);
            }

            case REPAIR_ANALYZE: {
                // Fix B: No file/line anchor in fallback task either
                String fallbackJson = """
                    {
                      "repair_steps": [
                        "Analyze the root cause of the test failure and produce a structured JSON diagnosis identifying the exact bug site, causal chain, and minimal fix strategy."
                      ],
                      "reasoning": "Fallback: Root cause analysis from failure evidence"
                    }
                    """;
                return PlannerOutput.fromJson(goal, fallbackJson);
            }

            case REPAIR_PATCH: {
                // Use diagnosed artifact from analysis if available, else analyzer heuristic
                String target;
                RootCauseAnalysis analysis = state.getLastRootCauseAnalysis();
                if (analysis != null && analysis.isValid() && analysis.getArtifactPath() != null) {
                    target = analysis.getArtifactPath();
                } else {
                    String derived = resolveRepairTarget(state.getFailingArtifact(), state);
                    target = derived != null ? derived : "src/ (explore with list_files to find the relevant file)";
                }
                String fallbackJson = """
                    {
                      "repair_steps": [
                        "Read %s, identify the root cause, and immediately apply the fix using replace_in_file. You MUST call replace_in_file before finishing."
                      ],
                      "reasoning": "Fallback: Atomic read-and-patch"
                    }
                    """.formatted(target);
                return PlannerOutput.fromJson(goal, fallbackJson);
            }

            case VALIDATE: {
                String fallbackJson = """
                    {
                      "repair_steps": [
                        "Step 1: Run tests to verify fix"
                      ],
                      "reasoning": "Fallback: Validation only"
                    }
                    """;
                return PlannerOutput.fromJson(goal, fallbackJson);
            }

            default: {
                return PlannerOutput.repairFallback(goal);
            }
        }
    }

    // ========================================================================
    // FAILURE CONTEXT BUILDING
    // ========================================================================

    private String buildFailureContext(SharedState state) {

        if (state.getLastTestResults() == null) {
            return "No test results available yet. Start by running tests to discover the failure.";
        }

        StringBuilder ctx = new StringBuilder();
        ctx.append("Failure Context:\n");
        ctx.append("- Failure type: ")
           .append(state.getLastTestResults().getFailureType())
           .append("\n");

        String snippet = state.getLastTestResults().getErrorSnippet();
        if (snippet != null && !snippet.isEmpty()) {
            ctx.append("- Error:\n").append(snippet).append("\n");
        }

        String rawOutput = state.getLastTestResults().getRawOutput();
        if (rawOutput != null && !rawOutput.isEmpty()) {
            String truncated = rawOutput.length() > 1200
                ? rawOutput.substring(0, 1200) + "..."
                : rawOutput;
            ctx.append("- Pytest output:\n").append(truncated).append("\n");
        }

        String artifact = state.getFailingArtifact();
        if (artifact != null) {
            ctx.append("- Analyzer-identified frame: ").append(artifact)
               .append(" (heuristic — actual root cause may differ)\n");
        }

        String reason = state.getCollectionFailureReason();
        if (reason != null) {
            ctx.append("- Collection failure reason: ").append(reason).append("\n");
        }

        return ctx.toString();
    }

    @Override
    public void handleTask(String taskId) {
        // Not used
    }
}