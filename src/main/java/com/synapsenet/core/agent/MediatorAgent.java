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
import com.synapsenet.core.state.RepairState;
import com.synapsenet.core.state.RootCauseAnalysis;
import com.synapsenet.core.state.SharedState;

/**
 * MediatorAgent — Phase-aware decision logic.
 *
 * @version FSM_v1.0_FINAL
 *
 * ARCHITECTURAL CONTRACT (enforced structurally):
 *
 *   The Mediator DECIDES. The Orchestrator EXECUTES transitions.
 *
 *   This class MUST NOT mutate SharedState in any way.
 *   It may only READ from SharedState and ExecutionResult.
 *
 * Changes vs prior version:
 *   [FIX Low #17] handleValidatePhase(): Added MAX_RETRIES_PER_TASK cap to the
 *                 !wereRun() RETRY path. Previously VALIDATE was the only phase
 *                 without a per-task cap on the "tests not run" path, allowing up
 *                 to MAX_TOTAL_ITERATIONS (20) to burn globally before termination.
 *                 Now matches the retry cap symmetry of all other phase handlers.
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
        log.info("[Mediator] Phase: {}, RepairState: {}",
                state.getCurrentPhase(), state.getRepairState());
        log.info("[Mediator] Iteration: {}/{}, Attempts on task: {}/{}",
                state.getTotalIterations(), MAX_TOTAL_ITERATIONS,
                state.getAttemptsOnCurrentTask(), MAX_RETRIES_PER_TASK);
        log.info("[Mediator] Tool execution: {}", executionResult.getToolExecutionSummary());
        log.info("[Mediator] Tests run: {}, exitCode: {}, Has patch: {}",
                executionResult.getTestResults().wereRun(),
                executionResult.getTestExitCode(),
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

        if (executionResult.hasErrors()) {
            TestResults testResults = executionResult.getTestResults();

            if (testResults != null && testResults.wereRun()) {
                log.info("[Mediator] Non-zero exit but tests ran — treating as test failure, not tool error");
                // Fall through to phase-aware logic
            } else {
                if (state.getCurrentPhase() == RepairPhase.REPAIR_PATCH) {
                    return handleRepairPatchToolError(executionResult.getErrorSummary(), state);
                }

                if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
                    log.warn("[Mediator] Tool error cap reached in {} → REPLAN",
                            state.getCurrentPhase());
                    return MediationResult.replan(
                            "Repeated tool errors in " + state.getCurrentPhase(),
                            state.getTotalIterations()
                    );
                }

                log.warn("[Mediator] Tool execution failed → RETRY: {}", executionResult.getErrorSummary());
                return MediationResult.retry(
                        "Tool execution errors: " + executionResult.getErrorSummary(),
                        state.getTotalIterations()
                );
            }
        }

        // ================================================================
        // PHASE-AWARE LOGIC
        // ================================================================

        return switch (state.getCurrentPhase()) {
            case REPRODUCE      -> handleReproducePhase(executionResult, state);
            case REPAIR_ANALYZE -> handleRepairAnalyzePhase(executionResult, state);
            case REPAIR_PATCH   -> handleRepairPatchPhase(executionResult, state);
            case VALIDATE       -> handleValidatePhase(executionResult, state);
        };
    }

    /**
     * REPRODUCE phase handler.
     */
    private MediationResult handleReproducePhase(ExecutionResult result, SharedState state) {

        if (!result.getTestResults().wereRun()) {
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

        int exitCode = result.getTestExitCode();

        if (exitCode != 0) {
            log.info("[Mediator] REPRODUCE: exitCode={} — failure confirmed → ADVANCE to REPAIR_ANALYZE",
                    exitCode);
            return MediationResult.advance(
                    "Bug reproduced - advancing to repair analysis phase",
                    state.getTotalIterations()
            );
        }

        RepairState repairState = state.getRepairState();

        if (repairState == RepairState.INITIAL) {
            log.info("[Mediator] REPRODUCE: exitCode=0, repairState=INITIAL → SUCCESS (NO_FAILURE_FOUND)");
            return MediationResult.success(
                    "Tests already passing - no failure to repair",
                    state.getTotalIterations()
            );
        }

        log.warn("[Mediator] REPRODUCE: exitCode=0 but repairState={} — no validated patch → RETRY",
                repairState);
        return MediationResult.retry(
                "Unexpected pass in REPRODUCE (repairState=" + repairState + ") — possible flaky test",
                state.getTotalIterations()
        );
    }

    /**
     * REPAIR_ANALYZE phase handler.
     */
    private MediationResult handleRepairAnalyzePhase(ExecutionResult result, SharedState state) {

        if (state.hasValidRootCauseAnalysis()) {
            log.info("[Mediator] REPAIR_ANALYZE: Valid analysis → ADVANCE to REPAIR_PATCH");
            log.info("[Mediator] Analysis: {}", state.getLastRootCauseAnalysis());
            return MediationResult.advance(
                    "Root cause analysis validated - advancing to patch phase",
                    state.getTotalIterations()
            );
        }

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
     *
     * [FIX item 5] This method no longer mutates SharedState.
     * state.setLastToolError() and state.incrementConsecutiveToolErrors() have been
     * moved to SimpleTaskOrchestrator.
     *
     * IMPORTANT: consecutiveToolErrors is read here PRE-INCREMENT.
     */
    private MediationResult handleRepairPatchToolError(String errorSummary, SharedState state) {

        int preIncrementErrors = state.getConsecutiveToolErrors();
        log.warn("[Mediator] REPAIR_PATCH tool error (will be consecutive={}): {}",
                preIncrementErrors + 1,
                errorSummary.length() > 120 ? errorSummary.substring(0, 120) + "..." : errorSummary);

        if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
            log.warn("[Mediator] REPAIR_PATCH tool error cap reached ({}/{}) → REPLAN",
                    state.getAttemptsOnCurrentTask(), MAX_RETRIES_PER_TASK);
            return MediationResult.replan(
                    "Repeated tool-level failures in REPAIR_PATCH — capped",
                    state.getTotalIterations()
            );
        }

        if (errorSummary.contains("not found")) {
            if (preIncrementErrors >= 1) {
                log.warn("[Mediator] REPAIR_PATCH: Search block not found twice consecutively → REPLAN");
                return MediationResult.replan(
                        "Search block not found on two consecutive attempts — need new strategy",
                        state.getTotalIterations()
                );
            }
            log.warn("[Mediator] REPAIR_PATCH: Search block not found → RETRY (proposedSearchBlock injected)");
            return MediationResult.retry(
                    "Search block not found — retrying with injected proposedSearchBlock",
                    state.getTotalIterations()
            );
        }

        if (errorSummary.contains("multiple times")) {
            if (preIncrementErrors >= 1) {
                log.warn("[Mediator] REPAIR_PATCH: Ambiguous search block failed twice → REPLAN");
                return MediationResult.replan(
                        "Search block ambiguous on two consecutive attempts — need new strategy",
                        state.getTotalIterations()
                );
            }
            log.warn("[Mediator] REPAIR_PATCH: Ambiguous search block — retrying");
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
     */
    private MediationResult handleRepairPatchPhase(ExecutionResult result, SharedState state) {

        boolean hasPatch = result.getModifiedFiles() != null &&
                           !result.getModifiedFiles().isEmpty();

        if (hasPatch) {
            log.info("[Mediator] REPAIR_PATCH: Patch applied → ADVANCE to VALIDATE");
            return MediationResult.advance(
                    "Patch applied - advancing to validation phase",
                    state.getTotalIterations()
            );
        }

        if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
            log.warn("[Mediator] REPAIR_PATCH: Retry cap reached without patch → REPLAN");
            return MediationResult.replan(
                    "Retry cap reached without successful patch",
                    state.getTotalIterations()
            );
        }

        log.warn("[Mediator] REPAIR_PATCH: No patch applied → RETRY");
        return MediationResult.retry(
                "REPAIR_PATCH phase requires applying a patch",
                state.getTotalIterations()
        );
    }

    /**
     * VALIDATE phase handler.
     *
     * The ONLY place in this class where SUCCESS is returned for a repaired failure.
     *
     * [FIX Low #17] Added MAX_RETRIES_PER_TASK cap to the !wereRun() RETRY path.
     *
     * Previously VALIDATE was the only phase handler without this cap. If the LLM
     * consistently failed to produce a run_tests call, the system would RETRY up to
     * MAX_TOTAL_ITERATIONS (20) globally before terminating — burning iterations that
     * other phases would have bounded at 3. Now all four phase handlers are symmetric.
     *
     * Why REPLAN (not FAIL) on cap: consistent with how REPRODUCE handles the same
     * case. A new plan may generate a valid run_tests call.
     */
    private MediationResult handleValidatePhase(ExecutionResult result, SharedState state) {

        if (!result.getTestResults().wereRun()) {
            // [FIX Low #17] Per-task retry cap — matches REPRODUCE/REPAIR_ANALYZE symmetry.
            if (state.getAttemptsOnCurrentTask() >= MAX_RETRIES_PER_TASK) {
                log.warn("[Mediator] VALIDATE: Tests not run, retry cap reached ({}/{}) → REPLAN",
                        state.getAttemptsOnCurrentTask(), MAX_RETRIES_PER_TASK);
                return MediationResult.replan(
                        "VALIDATE retry cap reached without running tests",
                        state.getTotalIterations()
                );
            }
            log.warn("[Mediator] VALIDATE: Tests not run → RETRY");
            return MediationResult.retry(
                    "VALIDATE phase requires running tests",
                    state.getTotalIterations()
            );
        }

        int exitCode = result.getTestExitCode();

        if (exitCode == 0) {
            log.info("[Mediator] VALIDATE: exitCode=0 → VALIDATED_SUCCESS");
            List<String> modifiedFiles = state.getModifiedFiles();
            String patchDisplay = modifiedFiles.isEmpty()
                ? "No files modified"
                : "Modified: " + String.join(", ", modifiedFiles);
            return MediationResult.success(
                    patchDisplay,
                    state.getTotalIterations()
            );
        }

        log.warn("[Mediator] VALIDATE: exitCode={} → VALIDATED_FAILURE → REPLAN", exitCode);
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