package com.synapsenet.llm;

import com.synapsenet.core.agent.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * MockLLMClient — deterministic LLM stub for profile "mock".
 *
 * Purpose: force SynapseNet through one full REPRODUCE → REPAIR_ANALYZE cycle
 * to prove rollback invariants under controlled, reproducible conditions.
 *
 * Behaviour contract:
 *   REPRODUCE call 1  → list_files  (satisfies discovery gate)
 *   REPRODUCE call 2  → run_tests   (satisfies Mediator test-run requirement)
 *   REPRODUCE call 3+ → run_tests   (idempotent, handles any retry)
 *
 *   REPAIR_ANALYZE    → valid raw JSON RootCauseAnalysis (no tool_calls wrapper)
 *   REPAIR_PATCH      → replace_in_file with placeholder blocks
 *   VALIDATE          → run_tests
 *
 *   PLANNER           → minimal valid plan JSON
 *   MEDIATOR          → ADVANCE after test run; REPLAN otherwise
 *   CRITIC            → minimal valid critique JSON
 *
 * JSON schema rules derived from ExecutorAgent.parseToolCallsFromResponse():
 *   - Top-level object with "reasoning" (String) and "tool_calls" (Array)
 *   - Each element: {"tool": "<name>", "args": {<object>}}
 *   - "args" MUST be a JSON object node, never a string.
 *
 * REPAIR_ANALYZE schema derived from ExecutorAgent.parseAnalysis():
 *   - Raw JSON — NO tool_calls wrapper.
 *   - Fields: artifactPath, artifactLine, rootCauseSummary, causalExplanation,
 *             minimalFixStrategy, proposedSearchBlock, whyPreviousAttemptsFailed
 *
 * Discovery gate (ExecutorAgent.enforceDiscoveryGate):
 *   - If structureDiscovered==false AND no list_files/file_tree in calls → gate
 *     injects list_files and discards everything else.
 *   - Mock satisfies this by always emitting list_files as call 1.
 *
 * Phase-allowed tools (ExecutorAgent.getAllowedTools):
 *   REPRODUCE      → read_file, run_tests, grep, list_files, file_tree
 *   REPAIR_ANALYZE → (none — raw JSON path, never reaches filterToolsByPhase)
 *   REPAIR_PATCH   → read_file, replace_in_file, write_file, grep, list_files, file_tree
 *   VALIDATE       → run_tests
 */
@Component
@Profile("mock")
public class MockLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(MockLLMClient.class);

    // -------------------------------------------------------------------------
    // Call counters — one per agent type, reset-safe for multi-run test harness
    // -------------------------------------------------------------------------

    /** Counts calls to generateWithRole(EXECUTOR, ...) */
    private final AtomicInteger executorCallCount   = new AtomicInteger(0);

    /** Counts calls to generateWithRole(PLANNER, ...) */
    private final AtomicInteger plannerCallCount    = new AtomicInteger(0);

    /** Counts calls to generateWithRole(MEDIATOR, ...) */
    private final AtomicInteger mediatorCallCount   = new AtomicInteger(0);

    /** Counts calls to generateWithRole(CRITIC, ...) */
    private final AtomicInteger criticCallCount     = new AtomicInteger(0);

    // -------------------------------------------------------------------------
    // Configurable test parameters — override via subclass or setter if needed
    // -------------------------------------------------------------------------

    /**
     * The test file path that run_tests will be invoked against.
     * Must match a real file in the workspace for the ToolExecutor to run pytest.
     * Adjust to match the actual test file present in the test workspace.
     */
    private String testFilePath = "tests/test_sample.py";

    /**
     * The source artifact path injected into the REPAIR_ANALYZE JSON response.
     * Should point to a real .py file the mock "diagnoses" as faulty.
     * Adjust to match the actual source file in the test workspace.
     */
    private String sourceArtifactPath = "src/sample.py";

    // -------------------------------------------------------------------------
    // LLMClient interface implementation
    // -------------------------------------------------------------------------

    /**
     * Primary dispatch method.
     *
     * Routes to a phase-appropriate response generator based on AgentType.
     * For EXECUTOR, further sub-dispatches by call count to sequence
     * list_files → run_tests across the REPRODUCE phase.
     *
     * @param agentType   The calling agent's role (EXECUTOR, PLANNER, MEDIATOR, CRITIC)
     * @param prompt      The full prompt text (inspected for phase hints when needed)
     * @param temperature Ignored — mock is deterministic
     * @return            A JSON string matching the schema expected by the calling agent
     */
    @Override
    public String generateWithRole(AgentType agentType, String prompt, double temperature) {

        log.info("[MockLLM] generateWithRole called: agentType={}", agentType);

        return switch (agentType) {
            case EXECUTOR -> handleExecutor(prompt);
            case PLANNER  -> handlePlanner(prompt);
            case MEDIATOR -> handleMediator(prompt);
            case CRITIC   -> handleCritic(prompt);
        };
    }

    @Override
    public double getTemperatureForRole(AgentType agentType) {
        return 0.0;   // deterministic — temperature is irrelevant for mock
    }

    // -------------------------------------------------------------------------
    // EXECUTOR responses
    // -------------------------------------------------------------------------

    /**
     * Sequence EXECUTOR responses by call count and phase.
     *
     * REPAIR_ANALYZE is detected by inspecting the prompt for the raw-JSON
     * instruction marker. ExecutorAgent routes REPAIR_ANALYZE through
     * executeRepairAnalyze() which calls generateWithRole(EXECUTOR, ...) and
     * passes the raw response directly to parseAnalysis() — no tool_calls
     * wrapper is parsed.
     *
     * All other EXECUTOR calls (REPRODUCE, REPAIR_PATCH, VALIDATE) go through
     * parseToolCallsFromResponse() and expect the standard tool_calls schema.
     */
    private String handleExecutor(String prompt) {
        int call = executorCallCount.incrementAndGet();
        log.info("[MockLLM] EXECUTOR call #{}", call);

        // REPAIR_ANALYZE detection:
        // ExecutorAgent.buildRepairAnalyzePrompt() always contains this unique marker.
        if (prompt.contains("Respond with ONLY this JSON") &&
                prompt.contains("artifactPath") &&
                prompt.contains("rootCauseSummary") &&
                !prompt.contains("tool_calls")) {
            log.info("[MockLLM] EXECUTOR: detected REPAIR_ANALYZE prompt → returning raw RCA JSON");
            return repairAnalyzeJson(prompt);
        }

        // VALIDATE phase detection:
        // buildExecutionPrompt() sets phase header "## CURRENT PHASE: VALIDATE"
        if (prompt.contains("CURRENT PHASE: VALIDATE") ||
                prompt.contains("VALIDATE: Run tests ONLY")) {
            log.info("[MockLLM] EXECUTOR: detected VALIDATE phase → returning run_tests");
            return validatePhaseJson();
        }

        // REPAIR_PATCH phase detection:
        if (prompt.contains("CURRENT PHASE: REPAIR_PATCH") ||
                prompt.contains("REPAIR_PATCH: Apply the fix")) {
            log.info("[MockLLM] EXECUTOR: detected REPAIR_PATCH phase → returning replace_in_file");
            return repairPatchJson(prompt);
        }

        // REPRODUCE phase — sequence by call count:
        //   Call 1 → list_files  (satisfies enforceDiscoveryGate)
        //   Call 2+ → run_tests  (satisfies Mediator test-run requirement)
        //
        // The discovery gate in ExecutorAgent checks structureDiscovered.
        // Once list_files returns successfully, ToolExecutor sets
        // structureDiscovered=true via state.setWorkspaceFileTree().
        // Subsequent calls can safely proceed to run_tests.
        if (call == 1) {
            log.info("[MockLLM] EXECUTOR call 1 → list_files (satisfying discovery gate)");
            return reproduceListFilesJson();
        } else {
            log.info("[MockLLM] EXECUTOR call {} → run_tests", call);
            return reproduceRunTestsJson();
        }
    }

    // -------------------------------------------------------------------------
    // PLANNER responses
    // -------------------------------------------------------------------------

    /**
     * Planner response.
     *
     * Returns a minimal PlannerOutput-compatible JSON.
     * PlannerAgent parses this — field names must match PlannerOutput's
     * deserialization schema. The plan contains two steps:
     *   1. Discover workspace structure (list_files — satisfies discovery gate)
     *   2. Run the failing test (run_tests — satisfies Mediator)
     *
     * On replan (call 2+), returns a single repair step so the system can
     * proceed through REPAIR_ANALYZE → REPAIR_PATCH.
     */
    private String handlePlanner(String prompt) {
        int call = plannerCallCount.incrementAndGet();
        log.info("[MockLLM] PLANNER call #{}", call);

        if (call == 1) {
            // Initial plan: discover then test
            return """
                    {
                      "goal": "Reproduce and repair the failing test",
                      "investigationSteps": [
                        "List workspace files to understand structure",
                        "Run the failing test to reproduce the failure"
                      ],
                      "currentStep": 0
                    }
                    """;
        } else {
            // Replan: single repair step — no test steps (validated by
            // SimpleTaskOrchestrator.generateAndValidateRepairPlan)
            return """
                    {
                      "goal": "Apply the diagnosed fix",
                      "investigationSteps": [
                        "Apply the fix identified in root cause analysis"
                      ],
                      "currentStep": 0
                    }
                    """;
        }
    }

    // -------------------------------------------------------------------------
    // MEDIATOR responses
    // -------------------------------------------------------------------------

    /**
     * Mediator response.
     *
     * MediatorAgent.decide() parses this JSON and maps "decision" to
     * MediationDecision enum values: SUCCESS, ADVANCE, RETRY, REPLAN, FAIL.
     *
     * Strategy:
     *   Call 1 (after list_files)  → RETRY  (tests haven't run yet)
     *   Call 2 (after run_tests)   → ADVANCE (tests ran, failure reproduced — advance to REPAIR_ANALYZE)
     *   Call 3 (after REPAIR_ANALYZE) → ADVANCE (analysis complete — advance to REPAIR_PATCH)
     *   Call 4 (after REPAIR_PATCH)   → ADVANCE (patch applied — advance to VALIDATE)
     *   Call 5 (after VALIDATE)       → SUCCESS (tests pass)
     *   Call 6+ → SUCCESS (defensive default)
     *
     * The prompt is inspected to detect whether tests actually ran, because
     * the Mediator must not return ADVANCE if tests haven't executed.
     * ExecutorAgent sets rawTestExitCode != -1 only when run_tests is called.
     */
    private String handleMediator(String prompt) {
        int call = mediatorCallCount.incrementAndGet();
        log.info("[MockLLM] MEDIATOR call #{}", call);

        // If the prompt contains test results (exitCode present and not -1),
        // the test ran. ADVANCE to move the FSM forward.
        // If no test results present yet, RETRY to let REPRODUCE continue.
        boolean testsRan = prompt.contains("exitCode=0") ||
                           prompt.contains("exitCode=1") ||
                           prompt.contains("exitCode=2") ||
                           prompt.contains("Test Results") ||
                           prompt.contains("passed") ||
                           prompt.contains("failed") ||
                           prompt.contains("FAILED") ||
                           prompt.contains("PASSED");

        String decision;
        String reasoning;

        if (!testsRan && call == 1) {
            // First call, no tests yet — retry so REPRODUCE can run tests
            decision  = "RETRY";
            reasoning = "Tests have not run yet. Retrying to allow run_tests to execute.";
        } else if (call <= 4) {
            // Tests ran or we are in a repair phase — advance the FSM
            decision  = "ADVANCE";
            reasoning = "Phase complete. Advancing to next phase.";
        } else {
            // VALIDATE phase complete — declare success
            decision  = "SUCCESS";
            reasoning = "All phases complete. Tests pass.";
        }

        log.info("[MockLLM] MEDIATOR call #{} → decision={}", call, decision);

        return String.format("""
                {
                  "decision": "%s",
                  "reasoning": "%s",
                  "confidence": 1.0
                }
                """, decision, reasoning);
    }

    // -------------------------------------------------------------------------
    // CRITIC responses
    // -------------------------------------------------------------------------

    /**
     * Critic response.
     *
     * CriticAgent.analyze() parses this JSON. Returns a minimal valid critique
     * that does not block FSM progression.
     */
    private String handleCritic(String prompt) {
        int call = criticCallCount.incrementAndGet();
        log.info("[MockLLM] CRITIC call #{}", call);

        return """
                {
                  "assessment": "ACCEPTABLE",
                  "issues": [],
                  "suggestions": [],
                  "reasoning": "Mock critic: execution acceptable, no issues detected."
                }
                """;
    }

    // -------------------------------------------------------------------------
    // EXECUTOR response builders
    // -------------------------------------------------------------------------

    /**
     * REPRODUCE call 1 — list_files.
     *
     * Satisfies ExecutorAgent.enforceDiscoveryGate():
     *   gate checks for "list_files" or "file_tree" in the call list.
     *   After ToolExecutor executes list_files successfully, it calls
     *   state.setWorkspaceFileTree(stdout) and structureDiscovered becomes true.
     *
     * Tool schema: list_files args = {"path": "<dir>"}
     *   Allowed in REPRODUCE: ✅ (getAllowedTools returns list_files)
     */
    private String reproduceListFilesJson() {
        return """
                {
                  "reasoning": "Discovering workspace structure before running tests.",
                  "tool_calls": [
                    {
                      "tool": "list_files",
                      "args": {
                        "path": "."
                      }
                    }
                  ]
                }
                """;
    }

    /**
     * REPRODUCE call 2+ — run_tests.
     *
     * Satisfies the Mediator's requirement that getTestExitCode() != -1.
     * ExecutorAgent.executeTools() sets rawTestExitCode from result.getExitCode()
     * when it processes a "run_tests" tool call.
     *
     * Tool schema: run_tests args = {"test_file": "<path>"}
     *   Allowed in REPRODUCE: ✅ (getAllowedTools returns run_tests)
     *
     * IMPORTANT: testFilePath must point to a real file in the workspace.
     * If the file does not exist, pytest will exit with code 4 (no tests
     * collected), which ExecutorAgent maps to COLLECTION_ERROR — still a
     * non -1 exit code, so the Mediator unblocks, but the system will
     * route to REPAIR_ANALYZE instead of SUCCESS. That is the correct
     * behaviour for a failing test scenario.
     */
    private String reproduceRunTestsJson() {
        return String.format("""
                {
                  "reasoning": "Running the failing test to reproduce the failure and capture exit code.",
                  "tool_calls": [
                    {
                      "tool": "run_tests",
                      "args": {
                        "test_file": "%s"
                      }
                    }
                  ]
                }
                """, testFilePath);
    }

    /**
     * REPAIR_ANALYZE — raw JSON RootCauseAnalysis.
     *
     * Parses the real failing artifact path and line number directly from the
     * prompt, which ExecutorAgent.buildRepairAnalyzePrompt() injects verbatim:
     *
     *   "Failing file: tests/test_bucket.py"
     *   "artifactLine" from the stack trace line "test_bucket.py:109"
     *
     * Parsing the prompt is correct here — the mock cannot call SharedState
     * directly, but the prompt is the contract surface ExecutorAgent exposes.
     * If the prompt changes format, the parser degrades gracefully to the
     * configured sourceArtifactPath fallback.
     *
     * JSON string rule: newlines inside string values MUST be escaped as \\n.
     */
    private String repairAnalyzeJson(String prompt) {

        String artifactPath = extractArtifactPathFromPrompt(prompt);
        int artifactLine    = extractArtifactLineFromPrompt(prompt);

        log.info("[MockLLM] REPAIR_ANALYZE: using artifactPath='{}' line={}",
                artifactPath, artifactLine);

        return String.format("""
                {
                  "artifactPath": "%s",
                  "artifactLine": %d,
                  "rootCauseSummary": "Mock diagnosis: fault identified at line %d of %s.",
                  "causalExplanation": "The mock executor identified the failing line from the structured state injected into the prompt.",
                  "minimalFixStrategy": "Apply the minimal correction at line %d to resolve the observed test failure.",
                  "proposedSearchBlock": "# placeholder — authoritative block extracted from real file by ExecutorAgent",
                  "whyPreviousAttemptsFailed": "N/A — first attempt"
                }
                """,
                artifactPath,
                artifactLine,
                artifactLine,
                artifactPath,
                artifactLine
        );
    }

    /** Logs up to 40 chars of context around the first occurrence of marker in prompt. */
    private void logMarkerContext(String prompt, String marker) {
        int idx = prompt.indexOf(marker);
        if (idx < 0) {
            log.info("[MockLLM DIAG] marker '{}' → NOT FOUND in prompt", marker);
            return;
        }
        int start = Math.max(0, idx - 20);
        int end   = Math.min(prompt.length(), idx + marker.length() + 20);
        log.info("[MockLLM DIAG] marker '{}' at idx={} → context: [{}]",
                marker, idx, prompt.substring(start, end).replace("\n", "\\n"));
    }

    /**
     * Extract the failing artifact path from the REPAIR_ANALYZE prompt.
     *
     * ExecutorAgent.buildRepairAnalyzePrompt() always injects:
     *   "Failing file: <path>\n"
     *
     * Secondary marker (also injected):
     *   "The reported failing artifact is: <path>"
     *
     * Falls back to sourceArtifactPath if neither marker is found.
     */
    private String extractArtifactPathFromPrompt(String prompt) {
        // Primary: "Failing file: tests/test_bucket.py"
        java.util.regex.Pattern primary = java.util.regex.Pattern.compile(
                "Failing file:\\s*(\\S+\\.py)");
        java.util.regex.Matcher m = primary.matcher(prompt);
        if (m.find()) {
            String found = m.group(1).trim();
            log.info("[MockLLM] REPAIR_ANALYZE: extracted artifactPath from 'Failing file' marker: {}", found);
            return found;
        }

        // Secondary: "The reported failing artifact is: tests/test_bucket.py"
        java.util.regex.Pattern secondary = java.util.regex.Pattern.compile(
                "reported failing artifact is:\\s*(\\S+\\.py)");
        m = secondary.matcher(prompt);
        if (m.find()) {
            String found = m.group(1).trim();
            log.info("[MockLLM] REPAIR_ANALYZE: extracted artifactPath from 'reported failing artifact' marker: {}", found);
            return found;
        }

        log.warn("[MockLLM] REPAIR_ANALYZE: could not extract artifactPath from prompt — using fallback: {}",
                sourceArtifactPath);
        return sourceArtifactPath;
    }

    /**
     * Extract the failing line number from the REPAIR_ANALYZE prompt.
     *
     * ExecutorAgent.buildRepairAnalyzePrompt() injects the raw pytest output which
     * contains stack frames in the form:
     *   "test_bucket.py:109: AssertionError"
     *   "test_bucket.py:109"
     *
     * Also checks the secondary format injected by the COLLECTION_ERROR path:
     *   "error near line 109"
     *
     * Falls back to 1 if no line number is found, which causes ExecutorAgent to
     * use MAX_FILE_LINES_FALLBACK for the file window — still valid, just less precise.
     */
    /**
     * Extract the failing line number from the REPAIR_ANALYZE prompt.
     *
     * Priority order:
     *   1. Structured state markers injected by ExecutorAgent — these come directly
     *      from SharedState and are always accurate. Matches:
     *        "failLine=109"           (from "File window for ... (failLine=109)")
     *        "Failing artifact line: 109"  (from "[State] Failing artifact line: 109")
     *        "artifactLine=109"       (from "[STATE SNAPSHOT] ... line=109")
     *
     *   2. Raw pytest stack frame — "tests/test_bucket.py:109: AssertionError"
     *      Less reliable: prompt builders may truncate, escape, or omit output.
     *
     *   3. "error near line 109" — COLLECTION_ERROR path secondary marker.
     *
     *   4. Fallback: 1 — triggers Mediator rejection, forces REPLAN. Last resort only.
     *
     * Design principle: mocks should bind to their own injected metadata (structured
     * state), not raw tool output. Structured state is deterministic; pytest output
     * is subject to truncation and formatting changes.
     */
    private int extractArtifactLineFromPrompt(String prompt) {

        // Priority 1: Structured state injected by ExecutorAgent.
        // ExecutorAgent.buildRepairAnalyzePrompt() injects these explicitly:
        //   "File window for tests/test_bucket.py: lines 29-166 (failLine=109)"
        //   "Failing artifact line: 109"   (from [State] log, echoed into prompt)
        //   "[STATE SNAPSHOT] artifact=tests/test_bucket.py, line=109"
        // These are authoritative — they come from SharedState, not pytest formatting.
        java.util.regex.Pattern stateSnapshot = java.util.regex.Pattern.compile(
                "(?:failLine|Failing artifact line|artifactLine)[=:\\s]+(\\d+)");
        java.util.regex.Matcher m = stateSnapshot.matcher(prompt);
        if (m.find()) {
            int line = Integer.parseInt(m.group(1));
            log.info("[MockLLM] REPAIR_ANALYZE: extracted artifactLine from state snapshot: {}", line);
            return line;
        }

        // Priority 2: Raw pytest stack frame — "tests/test_bucket.py:109: AssertionError"
        // Less reliable: prompt builders may truncate or escape pytest output.
        java.util.regex.Pattern stackFrame = java.util.regex.Pattern.compile(
                "\\.py:(\\d+)\\b");
        m = stackFrame.matcher(prompt);
        if (m.find()) {
            int line = Integer.parseInt(m.group(1));
            log.info("[MockLLM] REPAIR_ANALYZE: extracted artifactLine from stack frame: {}", line);
            return line;
        }

        // Priority 3: "error near line 109" — COLLECTION_ERROR secondary marker.
        java.util.regex.Pattern nearLine = java.util.regex.Pattern.compile(
                "error near line (\\d+)");
        m = nearLine.matcher(prompt);
        if (m.find()) {
            int line = Integer.parseInt(m.group(1));
            log.info("[MockLLM] REPAIR_ANALYZE: extracted artifactLine from 'near line' marker: {}", line);
            return line;
        }

        // Priority 4: Give up. Line=1 will fail Mediator validation (artifactLine
        // is 108 lines from known failure line 109, tolerance=50) and force REPLAN.
        log.warn("[MockLLM] REPAIR_ANALYZE: could not extract line number from prompt — using fallback: 1");
        return 1;
    }

    /**
     * REPAIR_PATCH — replace_in_file.
     *
     * Tool schema: replace_in_file args = {
     *   "path": "<file>",
     *   "search_block": "<exact match>",
     *   "replace_block": "<replacement>"
     * }
     *
     * The artifact path is extracted from the prompt (which contains the grounded
     * path from SharedState via buildExecutionPrompt). The search_block placeholder
     * will not match real file content — replace_in_file will return "not found",
     * the Mediator routes to REPLAN, and the snapshot restore fires. That is the
     * correct outcome: the mock's purpose is to exercise the rollback path, not
     * to produce a successful patch.
     *
     * Allowed in REPAIR_PATCH: ✅ (getAllowedTools returns replace_in_file)
     */
    private String repairPatchJson(String prompt) {
        String artifactPath = extractArtifactPathFromPrompt(prompt);
        log.info("[MockLLM] REPAIR_PATCH: using artifactPath='{}'", artifactPath);

        return String.format("""
                {
                  "reasoning": "Applying the fix identified in root cause analysis.",
                  "tool_calls": [
                    {
                      "tool": "replace_in_file",
                      "args": {
                        "path": "%s",
                        "search_block": "def placeholder():\\n    pass",
                        "replace_block": "def placeholder():\\n    return True"
                      }
                    }
                  ]
                }
                """, artifactPath);
    }

    /**
     * VALIDATE — run_tests.
     *
     * Identical tool to REPRODUCE run_tests, but only run_tests is allowed
     * in VALIDATE phase (getAllowedTools returns only "run_tests").
     *
     * Allowed in VALIDATE: ✅
     */
    private String validatePhaseJson() {
        return String.format("""
                {
                  "reasoning": "Running tests to validate the applied patch.",
                  "tool_calls": [
                    {
                      "tool": "run_tests",
                      "args": {
                        "test_file": "%s"
                      }
                    }
                  ]
                }
                """, testFilePath);
    }

    // -------------------------------------------------------------------------
    // Configuration setters (optional — for integration test customisation)
    // -------------------------------------------------------------------------

    /**
     * Set the test file path used in run_tests calls.
     * Default: "tests/test_sample.py"
     * Must be relative to workspaceRoot and must exist for pytest to collect tests.
     */
    public void setTestFilePath(String testFilePath) {
        this.testFilePath = testFilePath;
        log.info("[MockLLM] testFilePath set to: {}", testFilePath);
    }

    /**
     * Set the source artifact path used in REPAIR_ANALYZE and REPAIR_PATCH responses.
     * Default: "src/sample.py"
     * Must be a real .py file in the workspace for grounding to succeed.
     */
    public void setSourceArtifactPath(String sourceArtifactPath) {
        this.sourceArtifactPath = sourceArtifactPath;
        log.info("[MockLLM] sourceArtifactPath set to: {}", sourceArtifactPath);
    }

    /**
     * Reset all call counters.
     * Use between test runs in a shared Spring context to prevent counter bleed.
     */
    public void resetCounters() {
        executorCallCount.set(0);
        plannerCallCount.set(0);
        mediatorCallCount.set(0);
        criticCallCount.set(0);
        log.info("[MockLLM] All call counters reset.");
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    public int getExecutorCallCount() { return executorCallCount.get(); }
    public int getPlannerCallCount()  { return plannerCallCount.get();  }
    public int getMediatorCallCount() { return mediatorCallCount.get(); }
    public int getCriticCallCount()   { return criticCallCount.get();   }
}