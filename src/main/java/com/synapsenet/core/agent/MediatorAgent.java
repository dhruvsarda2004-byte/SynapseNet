package com.synapsenet.core.agent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.core.critic.CriticFeedback;
import com.synapsenet.core.executor.ExecutionResult;
import com.synapsenet.core.executor.TestResults;
import com.synapsenet.core.mediator.MediationResult;
import com.synapsenet.core.state.RepairPhase;
import com.synapsenet.core.state.RootCauseAnalysis;
import com.synapsenet.core.state.SharedState;

/**
 * MediatorAgent - Phase-aware state machine decision logic.
 *
 * ARCHITECTURAL BOUNDARY:
 * The Mediator DECIDES. The Orchestrator EXECUTES transitions.
 *
 * This class does NOT mutate SharedState on REPLAN.
 * State mutation (softReset, phase reset, workspace restore) is the
 * exclusive responsibility of SimpleTaskOrchestrator.
 */
@Component
public class MediatorAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(MediatorAgent.class);

    private static final int MAX_TOTAL_ITERATIONS = 20;
    private static final int MAX_RETRIES_PER_TASK = 3;

    @Override
    public String getAgentId() {
        return "mediator-agent-1";
    }

    @Override
    public AgentType getAgentType() {
        return AgentType.MEDIATOR;
    }

    public MediationResult decide(
            ExecutionResult executionResult,
            CriticFeedback critique,
            SharedState state
    ) {
        log.info("[Mediator] ===== STATE TRANSITION DECISION =====");
        log.info("[Mediator] Current Phase: {}", state.getCurrentPhase());
        log.info("[Mediator] Iteration: {}/{}, Attempts on task: {}/{}",
                state.getTotalIterations(), MAX_TOTAL_ITERATIONS,
                state.getAttemptsOnCurrentTask(), MAX_RETRIES_PER_TASK);
        log.info("[Mediator] Tool execution: {}", executionResult.getToolExecutionSummary());
        log.info("[Mediator] Tests run: {}, Tests pass: {}, Has patch: {}",
                executionResult.getTestResults().wereRun(),
                executionResult.testsPass(),
                !executionResult.getModifiedFiles().isEmpty());

        // ================================================================
        // TERMINATION CHECKS
        // ================================================================

        if (state.getTotalIterations() >= MAX_TOTAL_ITERATIONS) {
            log.warn("[Mediator] Max iterations reached → FAIL");
            return MediationResult.fail(
                    "Maximum iterations exceeded",
                    state.getTotalIterations()
            );
        }

        // ================================================================
        // TOOL vs TEST FAILURE BOUNDARY
        // ================================================================
        // Tool failures (replace_in_file errors, file not found) → phase-aware escalation
        // Test failures (pytest exit 1 with output) → fall through to phase logic
        // ================================================================

        if (executionResult.hasErrors()) {
            TestResults testResults = executionResult.getTestResults();

            if (testResults != null && testResults.wereRun()) {
                log.info("[Mediator] Non-zero exit but tests ran — treating as test failure, not tool error");
                // Fall through to phase-aware logic below
            } else {
                // Route REPAIR_PATCH tool errors through the escalation ladder.
                // REPAIR_ANALYZE has no tool calls so never hits this path.
                // All other phases get a capped retry.
                if (state.getCurrentPhase() == RepairPhase.REPAIR_PATCH) {
                    return handleRepairPatchToolError(executionResult.getErrorSummary(), state);
                }

                // REPRODUCE / VALIDATE: simple capped retry
                if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
                    log.warn("[Mediator] Tool error cap reached in {} → REPLAN",
                            state.getCurrentPhase());
                    return MediationResult.replan(
                            "Repeated tool errors in " + state.getCurrentPhase(),
                            state.getTotalIterations()
                    );
                }

                log.warn("[Mediator] Tool execution failed (tool-level error) → RETRY");
                log.warn("[Mediator] Error details: {}", executionResult.getErrorSummary());
                return MediationResult.retry(
                        "Tool execution errors: " + executionResult.getErrorSummary(),
                        state.getTotalIterations()
                );
            }
        }

        // ================================================================
        // PHASE-AWARE LOGIC
        // ================================================================

        RepairPhase currentPhase = state.getCurrentPhase();

        return switch (currentPhase) {
            case REPRODUCE     -> handleReproducePhase(executionResult, state);
            case REPAIR_ANALYZE -> handleRepairAnalyzePhase(executionResult, state);
            case REPAIR_PATCH  -> handleRepairPatchPhase(executionResult, state);
            case VALIDATE      -> handleValidatePhase(executionResult, state);
        };
    }

    /**
     * REPRODUCE phase handler.
     *
     * ✅ FIX: Retry cap is now enforced inside this handler.
     * Previously the cap was only checked globally in decide(), which was removed
     * to fix REPAIR ordering. That left REPRODUCE with no cap — allowing infinite
     * file_tree loops (4/3, 5/3, 6/3... attempts).
     *
     * Rule: if LLM keeps calling file_tree without running tests, REPLAN after 3
     * attempts so the Planner generates a better task ("Run tests to confirm failure").
     */
    private MediationResult handleReproducePhase(ExecutionResult result, SharedState state) {

        if (!result.getTestResults().wereRun()) {

            // ✅ FIX: Enforce retry cap inside REPRODUCE
            if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
                log.warn("[Mediator] REPRODUCE retry cap reached ({}/{}) without running tests → REPLAN",
                        state.getAttemptsOnCurrentTask(), MAX_RETRIES_PER_TASK);
                return MediationResult.replan(
                        "Retry cap reached in REPRODUCE without running tests",
                        state.getTotalIterations()
                );
            }

            log.warn("[Mediator] REPRODUCE: Tests not run → RETRY");
            return MediationResult.retry(
                    "REPRODUCE phase requires running tests",
                    state.getTotalIterations()
            );
        }

        if (!result.testsPass()) {
            log.info("[Mediator] REPRODUCE: Tests failed → Transition to REPAIR_ANALYZE");
            state.resetTaskAttempts();
            state.markFailureObserved();  // Mark that we SAW a failure
            return MediationResult.advance(
                    "Bug reproduced - advancing to repair analysis phase",
                    state.getTotalIterations()
            );
        }

        // SUCCESS INVARIANT
        // SUCCESS requires an actual patch application, not just analysis.
        // Analysis alone (lastRootCauseAnalysis != null) does NOT qualify as repair.
        boolean failureWasObserved = state.wasFailureObserved();
        boolean repairWasApplied   = !state.getRepairHistory().isEmpty();

        if (!failureWasObserved) {
            log.info("[Mediator] Tests passing and no prior failure → SUCCESS");
            return MediationResult.success(
                    "Tests already passing - no repair needed",
                    state.getTotalIterations()
            );
        }

        if (!repairWasApplied) {
            log.warn("[Mediator] Tests passing but no patch was applied → RETRY");
            return MediationResult.retry(
                    "Unexpected pass without patch application",
                    state.getTotalIterations()
            );
        }

        log.info("[Mediator] Tests passing AFTER patch application → SUCCESS");
        return MediationResult.success(
                "Failure resolved by patch",
                state.getTotalIterations()
        );
    }

    /**
     * REPAIR_ANALYZE phase handler.
     *
     * REPAIR_ANALYZE runs with no tool calls. The Executor parses the LLM's raw
     * JSON response into a RootCauseAnalysis object and stores it in SharedState.
     * This handler checks state.hasValidRootCauseAnalysis() — never parses JSON.
     *
     * Success condition (deterministic, all must be true):
     *   - RootCauseAnalysis stored and isValid() == true
     *   - artifactPath matches state.getFailingArtifact()
     *   - artifactLine within ±ARTIFACT_LINE_TOLERANCE of state.getFailingArtifactLine()
     *   - All required fields non-empty
     *
     * RETRY on invalid analysis. Retry cap → REPLAN.
     * REPLAN (not FAIL) — malformed analysis is a reasoning failure, not infrastructure.
     */
    private MediationResult handleRepairAnalyzePhase(ExecutionResult result, SharedState state) {

        // ✅ 1. Valid analysis present → ADVANCE to REPAIR_PATCH
        if (state.hasValidRootCauseAnalysis()) {
            log.info("[Mediator] REPAIR_ANALYZE: Valid analysis present → Transition to REPAIR_PATCH");
            log.info("[Mediator] Analysis: {}", state.getLastRootCauseAnalysis());
            state.resetTaskAttempts();
            return MediationResult.advance(
                    "Root cause analysis validated - advancing to patch phase",
                    state.getTotalIterations()
            );
        }

        // ✅ 2. Invalid analysis + retry cap → REPLAN
        if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
            log.warn("[Mediator] REPAIR_ANALYZE: Retry cap reached without valid analysis → REPLAN");

            RootCauseAnalysis analysis = state.getLastRootCauseAnalysis();
            String reason = analysis != null
                ? "Analysis invalid: " + analysis.getInvalidReason()
                : "No analysis produced";

            return MediationResult.replan(
                    "REPAIR_ANALYZE retry cap exceeded — " + reason,
                    state.getTotalIterations()
            );
        }

        // ✅ 3. Invalid analysis, retries remain → RETRY
        RootCauseAnalysis analysis = state.getLastRootCauseAnalysis();
        String reason = analysis != null
            ? analysis.getInvalidReason()
            : "no analysis JSON returned";

        log.warn("[Mediator] REPAIR_ANALYZE: Analysis invalid ({}) → RETRY", reason);
        return MediationResult.retry(
                "REPAIR_ANALYZE: invalid analysis — " + reason,
                state.getTotalIterations()
        );
    }

    /**
     * REPAIR_PATCH tool-error escalation ladder.
     * Identical logic to the old REPAIR escalation ladder — renamed for clarity.
     *
     * Error categories from RealToolExecutor:
     *   "Search block not found"           → REPLAN immediately (hallucinated content)
     *   "Search block found multiple times" → retry once, then REPLAN
     *   Other tool errors                  → capped retry
     */
    private MediationResult handleRepairPatchToolError(String errorSummary, SharedState state) {

        state.setLastToolError(errorSummary);
        state.incrementConsecutiveToolErrors();

        log.warn("[Mediator] REPAIR_PATCH tool error (consecutive={}): {}",
                state.getConsecutiveToolErrors(),
                errorSummary.length() > 120 ? errorSummary.substring(0, 120) + "..." : errorSummary);

        // Absolute cap
        if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
            log.warn("[Mediator] REPAIR_PATCH tool error cap reached ({}/{}) → REPLAN",
                    state.getAttemptsOnCurrentTask(), MAX_RETRIES_PER_TASK);
            return MediationResult.replan(
                    "Repeated tool-level failures in REPAIR_PATCH — capped",
                    state.getTotalIterations()
            );
        }

        if (errorSummary.contains("not found")) {
            log.warn("[Mediator] REPAIR_PATCH: Search block not found → REPLAN immediately");
            return MediationResult.replan(
                    "Search block not found — LLM used content not in file",
                    state.getTotalIterations()
            );
        }

        if (errorSummary.contains("multiple times")) {
            if (state.getConsecutiveToolErrors() >= 2) {
                log.warn("[Mediator] REPAIR_PATCH: Ambiguous search block failed twice → REPLAN");
                return MediationResult.replan(
                        "Search block ambiguous on two consecutive attempts — need new strategy",
                        state.getTotalIterations()
                );
            }
            log.warn("[Mediator] REPAIR_PATCH: Ambiguous search block — retrying with error feedback");
            return MediationResult.retry(
                    "Search block found multiple times — retrying with longer context constraint",
                    state.getTotalIterations()
            );
        }

        log.warn("[Mediator] REPAIR_PATCH: Other tool error → RETRY");
        return MediationResult.retry(
                "Tool error in REPAIR_PATCH: " + errorSummary,
                state.getTotalIterations()
        );
    }

    /**
     * REPAIR_PATCH phase handler.
     *
     * Invariant (order is critical):
     *   1. hasPatch check FIRST → ADVANCE (even on attempt 3/3)
     *   2. retry cap check SECOND → REPLAN
     *   3. otherwise → RETRY
     */
    private MediationResult handleRepairPatchPhase(ExecutionResult result, SharedState state) {

        boolean hasPatch = result.getModifiedFiles() != null &&
                           !result.getModifiedFiles().isEmpty();

        // ✅ 1. Patch succeeded → ADVANCE to VALIDATE
        if (hasPatch) {
            log.info("[Mediator] REPAIR_PATCH: Patch applied → Transition to VALIDATE");
            state.resetTaskAttempts();
            state.clearToolErrorState();
            return MediationResult.advance(
                    "Patch applied - advancing to validation phase",
                    state.getTotalIterations()
            );
        }

        // ✅ 2. No patch + retry cap → REPLAN
        if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
            log.warn("[Mediator] REPAIR_PATCH: Retry cap reached without patch → REPLAN");
            return MediationResult.replan(
                    "Retry cap reached without successful patch",
                    state.getTotalIterations()
            );
        }

        // ✅ 3. No patch, retries remain → RETRY
        log.warn("[Mediator] REPAIR_PATCH: No patch applied → RETRY");
        return MediationResult.retry(
                "REPAIR_PATCH phase requires applying a patch",
                state.getTotalIterations()
        );
    }

    private boolean isTestFile(String filePath) {
        if (filePath == null) return false;

        String normalized = filePath.replace('\\', '/');

        if (normalized.startsWith("testing/") || normalized.startsWith("tests/") ||
            normalized.contains("/testing/") || normalized.contains("/tests/")) {
            return true;
        }

        int lastSlash = normalized.lastIndexOf('/');
        String filename = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        return filename.startsWith("test_") || filename.endsWith("_test.py");
    }

    /**
     * VALIDATE phase handler.
     *
     * Returns REPLAN (not RETRY) when tests fail — triggers workspace restore
     * and returns to REPRODUCE. RETRY here would loop validate→validate forever.
     */
    private MediationResult handleValidatePhase(ExecutionResult result, SharedState state) {

        if (!result.getTestResults().wereRun()) {
            log.warn("[Mediator] VALIDATE: Tests not run → RETRY");
            return MediationResult.retry(
                    "VALIDATE phase requires running tests",
                    state.getTotalIterations()
            );
        }

        if (result.testsPass()) {
            log.info("[Mediator] VALIDATE: Tests pass → SUCCESS");
            List<String> modifiedFiles = state.getModifiedFiles();
            String patchDisplay = modifiedFiles.isEmpty()
                ? "No files modified"
                : "Modified: " + String.join(", ", modifiedFiles);

            return MediationResult.success(
                    patchDisplay,
                    state.getTotalIterations()
            );
        }

        log.warn("[Mediator] VALIDATE: Tests still failing → REPLAN");
        state.resetTaskAttempts();
        return MediationResult.replan(
                "Patch didn't fix the bug - need new repair strategy",
                state.getTotalIterations()
        );
    }

    @Override
    public void handleTask(String taskId) {
        // Not used
    }
}