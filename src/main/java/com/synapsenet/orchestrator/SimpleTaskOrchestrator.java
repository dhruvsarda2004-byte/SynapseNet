package com.synapsenet.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.core.agent.PlannerAgent;
import com.synapsenet.core.agent.ExecutorAgent;
import com.synapsenet.core.agent.CriticAgent;
import com.synapsenet.core.agent.MediatorAgent;
import com.synapsenet.core.critic.CriticFeedback;
import com.synapsenet.core.executor.ExecutionResult;
import com.synapsenet.core.executor.PythonExecutor;
import com.synapsenet.core.filesystem.FileSystemManager;
import com.synapsenet.core.mediator.MediationDecision;
import com.synapsenet.core.mediator.MediationResult;
import com.synapsenet.core.planner.PlannerOutput;
import com.synapsenet.core.state.SharedState;
import com.synapsenet.core.state.RepairPhase;
import com.synapsenet.core.state.RepairAttempt;
import com.synapsenet.core.state.RootCauseAnalysis;
import com.synapsenet.core.state.RepairState;
import com.synapsenet.core.state.RepairLifecycle;
// [PathNormalizer] Import retained for reference — snapshot predicates migrated to p->true (Fix 1).
// Remove this import if compiler warns unused.

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.IOException;

/**
 * SimpleTaskOrchestrator — top-level controller for the Debugger CIR loop.
 *
 * @version FSM_v1.0_FINAL
 *
 * Phase flow:  REPRODUCE -> REPAIR_ANALYZE -> REPAIR_PATCH -> VALIDATE
 *
 * Returns Map<String, Object> — no external DTO needed.
 * Keys: "success" (Boolean), "totalIterations" (Integer),
 *       "status" (String), "details" (String)
 *
 * Changes vs prior version:
 *   [FIX Low #12] Null-task replan path now calls state.incrementReplanCount()
 *                 in addition to incrementing the local consecutiveReplans counter.
 *                 Previously these replans were invisible to the global replan counter,
 *                 causing benchmark JSON replan_count to undercount total replans.
 *   [PathNormalizer] Snapshot predicates now use PathNormalizer.normalize() instead of
 *                    inline replace()/startsWith() chains. This closes silent snapshot-miss
 *                    bugs where LLM-generated paths like "././file.py" or "../file.py"
 *                    survived partial normalization and failed the endsWith() comparison.
 */
@Component
public class SimpleTaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SimpleTaskOrchestrator.class);

    private final PlannerAgent      planner;
    private final ExecutorAgent     executor;
    private final CriticAgent       critic;
    private final MediatorAgent     mediator;
    private final FileSystemManager fileSystemManager;
    private final PythonExecutor    pythonExecutor;

    private static final int MAX_CONSECUTIVE_REPLANS     = 3;
    private static final int MAX_PLAN_VALIDATION_RETRIES = 2;

    public SimpleTaskOrchestrator(
            PlannerAgent      planner,
            ExecutorAgent     executor,
            CriticAgent       critic,
            MediatorAgent     mediator,
            FileSystemManager fileSystemManager,
            PythonExecutor    pythonExecutor
    ) {
        this.planner           = planner;
        this.executor          = executor;
        this.critic            = critic;
        this.mediator          = mediator;
        this.fileSystemManager = fileSystemManager;
        this.pythonExecutor    = pythonExecutor;
    }

    // =========================================================================
    // MAIN ENTRY POINT
    // =========================================================================

    public Map<String, Object> runTask(String goal) {

        log.info("========== SYNAPSENET LOOP START ==========");
        long startTime = System.currentTimeMillis();

        String workspace = fileSystemManager.getWorkspacePath();
        if (fileSystemManager.fileExists("setup.py")
                || fileSystemManager.fileExists("pyproject.toml")
                || fileSystemManager.fileExists("setup.cfg")) {

            log.info("[PreFlight] Installing repo as editable package...");
            int installExit = runPreflightCommand(
                    workspace,
                    pythonExecutor.getPythonExecutable(),
                    "-m", "pip", "install", "-e", "."
            );
            if (installExit != 0) {
                log.error("[PreFlight] pip install -e . failed (exit {}). Aborting.", installExit);
                return failResult(new SharedState(goal), "Pre-flight failed: pip install -e . returned " + installExit);
            }
            log.info("[PreFlight] pip install -e . succeeded.");
        } else {
            log.info("[PreFlight] No setup.py / pyproject.toml found — skipping editable install.");
        }

        log.info("[PreFlight] Verifying pytest CLI...");
        int pytestVersionExit = runPreflightCommand(
                workspace,
                pythonExecutor.getPythonExecutable(),
                "-m", "pytest", "--version"
        );
        if (pytestVersionExit != 0) {
            log.error("[PreFlight] python -m pytest --version failed (exit {}). " +
                      "Environment is incompatible — aborting before FSM.", pytestVersionExit);
            return failResult(new SharedState(goal),
                    "Pre-flight failed: pytest CLI unavailable (exit " + pytestVersionExit + "). " +
                    "Check Python version and dependency compatibility.");
        }
        log.info("[PreFlight] Environment validated.");

        // [FIX item 10] workspaceSnapshot is method-local — not an instance field.
        FileSystemManager.WorkspaceSnapshot workspaceSnapshot = null;
        String t0Hash = null;

        SharedState state              = new SharedState(goal);
        int         consecutiveReplans = 0;

        PlannerOutput plan = planner.generatePlan(goal, state);
        state.updatePlan(plan);

        while (true) {

            if (state.getLifecycle().isTerminal()) {
                String msg = String.format(
                    "[Orchestrator] ILLEGAL: loop continued after terminal state %s",
                    state.getRepairState());
                log.error(msg);
                throw new IllegalStateException(msg);
            }

            state.incrementTotalIterations();
            String currentTask = state.getCurrentTask();

            // -----------------------------------------------------------------
            // NULL TASK — planner failed to produce a valid step
            // -----------------------------------------------------------------
            if (currentTask == null) {

                log.warn("[Orchestrator] No current task. Triggering replan.");
                consecutiveReplans++;

                // [FIX Low #12] Increment global replanCount on null-task replan path.
                state.incrementReplanCount();

                if (consecutiveReplans >= MAX_CONSECUTIVE_REPLANS) {
                    log.error("[Orchestrator] Planner failed {} times. Aborting.", MAX_CONSECUTIVE_REPLANS);
                    state.getLifecycle().transitionTo(RepairState.ABORTED);
                    log.info("[FSM FINAL STATE] {}", state.getRepairState());
                    logBenchmark(state, goal, startTime, false, "Planner unable to generate valid plan");
                    return failResult(state, "Planner unable to generate valid plan");
                }

                state.softReset();
                state.setCurrentPhase(RepairPhase.REPRODUCE);
                log.info("[Orchestrator] Soft reset — file cache preserved, phase reset to REPRODUCE");

                plan = planner.revisePlan(goal, state);
                state.updatePlan(plan);
                continue;
            }

            consecutiveReplans = 0;
            state.incrementTaskAttempts();

            assertValidPhaseState(state.getCurrentPhase(), state.getRepairState());

            // -----------------------------------------------------------------
            // EXECUTE -> CRITIQUE -> DECIDE
            // -----------------------------------------------------------------

            ExecutionResult executionResult = executor.execute(currentTask, state);

            if (executionResult == null) {
                log.error("[Orchestrator] Executor returned null — infrastructure failure -> ABORTED");
                state.getLifecycle().transitionTo(RepairState.ABORTED);
                log.info("[FSM FINAL STATE] {}", state.getRepairState());
                logBenchmark(state, goal, startTime, false, "Executor returned null");
                return failResult(state, "Infrastructure failure: executor returned null");
            }

            if (executionResult.hasErrors()
                    && executionResult.getErrorSummary() != null
                    && executionResult.getErrorSummary().startsWith("EXTERNAL_ARTIFACT:")) {
                String externalPath = executionResult.getErrorSummary()
                        .substring("EXTERNAL_ARTIFACT:".length()).trim();
                log.error("[Orchestrator] LLM proposed artifact '{}' not found in workspace -> ABORTED",
                          externalPath);
                state.getLifecycle().transitionTo(RepairState.ABORTED);
                log.info("[FSM FINAL STATE] {}", state.getRepairState());
                logBenchmark(state, goal, startTime, false,
                        "External artifact — path not in workspace: " + externalPath);
                return failResult(state,
                        "Aborted: LLM proposed file path '" + externalPath +
                        "' which does not exist in the workspace. " +
                        "Verify that the repo layout matches expected paths.");
            }

            if (executionResult.getTestResults() != null
                    && executionResult.getTestResults().wereRun()) {
                state.setLastTestResults(executionResult.getTestResults());
                log.info("[Orchestrator] Updated test results: {}",
                        executionResult.getTestResults().getSummary());
            }

            state.recordExecution(executionResult);

            RepairPhase currentPhase = state.getCurrentPhase();
            if ((currentPhase == RepairPhase.REPRODUCE || currentPhase == RepairPhase.VALIDATE)
                    && executionResult.getTestResults().wereRun()
                    && executionResult.getTestExitCode() == -1) {
                log.error("[Orchestrator] Tests ran but exitCode==-1 — infrastructure failure -> ABORTED");
                state.getLifecycle().transitionTo(RepairState.ABORTED);
                log.info("[FSM FINAL STATE] {}", state.getRepairState());
                logBenchmark(state, goal, startTime, false, "Infrastructure failure: no test exit code");
                return failResult(state, "Infrastructure failure: tests ran but exit code unavailable");
            }

            CriticFeedback critique = critic.analyze(goal, executionResult, state);
            state.recordCritique(critique);

            MediationResult mediation = mediator.decide(executionResult, critique, state);
            log.info("[Orchestrator] Mediator: {}", mediation);

            // [FIX item 5] REPAIR_PATCH tool error bookkeeping — Mediator is read-only.
            if (state.getCurrentPhase() == RepairPhase.REPAIR_PATCH
                    && executionResult.hasErrors()
                    && !executionResult.getTestResults().wereRun()) {
                state.setLastToolError(executionResult.getErrorSummary());
                state.incrementConsecutiveToolErrors();
                log.info("[Orchestrator] REPAIR_PATCH tool error state updated (consecutive={})",
                        state.getConsecutiveToolErrors());
            }

            MediationDecision decision = mediation.getDecision();

            // =================================================================
            // SUCCESS
            // =================================================================
            if (decision == MediationDecision.SUCCESS) {

                RepairLifecycle lifecycle = state.getLifecycle();
                RepairPhase phaseOnSuccess = state.getCurrentPhase();
                RepairState repairStateOnSuccess = state.getRepairState();

                if (phaseOnSuccess == RepairPhase.VALIDATE) {
                    lifecycle.transitionTo(RepairState.VALIDATED_SUCCESS);
                } else if (phaseOnSuccess == RepairPhase.REPRODUCE
                        && repairStateOnSuccess == RepairState.INITIAL) {
                    lifecycle.transitionTo(RepairState.NO_FAILURE_FOUND);
                } else {
                    String msg = String.format(
                        "[Orchestrator] ILLEGAL SUCCESS: phase=%s, repairState=%s",
                        phaseOnSuccess, repairStateOnSuccess);
                    log.error(msg);
                    throw new IllegalStateException(msg);
                }

                log.info("========== SYNAPSENET SUCCESS ({}) ==========", state.getRepairState());
                log.info("[FSM FINAL STATE] {}", state.getRepairState());
                exportWorkspaceMetadata(state, 0);
                logBenchmark(state, goal, startTime, true, mediation.getReasoning());

                return successResult(state, mediation.getReasoning());
            }

            // =================================================================
            // FAIL
            // =================================================================
            if (decision == MediationDecision.FAIL) {

                state.getLifecycle().transitionTo(RepairState.ABORTED);
                log.warn("========== SYNAPSENET FAILED ==========");
                log.info("[FSM FINAL STATE] {}", state.getRepairState());
                exportWorkspaceMetadata(state, 1);
                logBenchmark(state, goal, startTime, false, mediation.getReasoning());

                return failResult(state, mediation.getReasoning());
            }

            // =================================================================
            // ADVANCE
            // =================================================================
            if (decision == MediationDecision.ADVANCE) {

                RepairPhase phase = state.getCurrentPhase();

                // REPRODUCE -> REPAIR_ANALYZE
                if (phase == RepairPhase.REPRODUCE) {

                    log.info("[Orchestrator] Transition: REPRODUCE -> REPAIR_ANALYZE");
                    state.getLifecycle().transitionTo(RepairState.FAILURE_REPRODUCED);
                    state.resetTaskAttempts();
                    state.setCurrentPhase(RepairPhase.REPAIR_ANALYZE);

                    if (workspaceSnapshot == null) {
                        try {
                            // [Fix 1] Full workspace snapshot — p -> true.
                            // The artifact-grounded predicate was incomplete: it missed files
                            // that were patched but not in the predicate's closed set, causing
                            // them to be deleted during restore. A full snapshot is mathematically
                            // deterministic — no patched file can be lost.
                            //
                            // Snapshot is taken ONCE here. Never partially. Never expanded.
                            workspaceSnapshot = fileSystemManager.snapshotWorkspace(p -> true);
                            String failingArtifact = state.getFailingArtifact();
                            log.info("[Orchestrator] Full workspace snapshot taken ({} files, artifact: {})",
                                    workspaceSnapshot.size(), failingArtifact);
                            log.info("[TIMING DEBUG] Snapshot created at REPRODUCE -> REPAIR_ANALYZE");
                            log.info("[TIMING DEBUG] Snapshot identity={}",
                                    System.identityHashCode(workspaceSnapshot));
                            log.info("[SNAPSHOT REF] workspaceSnapshot assigned. identity={}",
                                    System.identityHashCode(workspaceSnapshot));
                            try {
                                t0Hash = fileSystemManager.hashEntireWorkspace(
                                        java.nio.file.Paths.get(fileSystemManager.getWorkspacePath()));
                                log.info("[WORKSPACE HASH] T0={}", t0Hash);
                            } catch (Exception e) {
                                log.error("[WORKSPACE HASH] Failed to compute T0 hash", e);
                            }
                        } catch (FileSystemManager.FileSystemException e) {
                            log.error("[Orchestrator] Snapshot failed", e);
                            logBenchmark(state, goal, startTime, false,
                                    "Workspace snapshot failed: " + e.getMessage());
                            return failResult(state, "Workspace snapshot failed: " + e.getMessage());
                        }
                    }

                    state.clearRootCauseAnalysis();

                    PlannerOutput analyzePlan = planner.generatePlan(goal, state);
                    state.updatePlan(analyzePlan);
                    continue;
                }

                // REPAIR_ANALYZE -> REPAIR_PATCH
                if (phase == RepairPhase.REPAIR_ANALYZE) {

                    log.info("[Orchestrator] Transition: REPAIR_ANALYZE -> REPAIR_PATCH");
                    state.getLifecycle().transitionTo(RepairState.ANALYSIS_COMPLETE);
                    state.resetTaskAttempts();
                    state.clearToolErrorState();
                    state.setCurrentPhase(RepairPhase.REPAIR_PATCH);

                    // [Fix 1] Snapshot expansion removed. The full workspace snapshot was
                    // taken at REPRODUCE -> REPAIR_ANALYZE and is used as-is.
                    // Do NOT re-snapshot — it would reset the baseline and break rollback
                    // integrity on subsequent REPLAN cycles.

                    PlannerOutput patchPlan = generateAndValidateRepairPlan(goal, state);
                    state.updatePlan(patchPlan);
                    continue;
                }

                // REPAIR_PATCH -> VALIDATE
                if (phase == RepairPhase.REPAIR_PATCH) {

                    log.info("[Orchestrator] Transition: REPAIR_PATCH -> VALIDATE");
                    state.getLifecycle().transitionTo(RepairState.PATCH_ATTEMPTED);
                    state.resetTaskAttempts();
                    state.clearToolErrorState();
                    state.setCurrentPhase(RepairPhase.VALIDATE);

                    PlannerOutput validatePlan = planner.generatePlan(goal, state);
                    state.updatePlan(validatePlan);
                    continue;
                }

                // VALIDATE complete
                if (phase == RepairPhase.VALIDATE) {
                    log.info("[Orchestrator] Validation complete — advancing task.");
                    state.advanceToNextTask();
                    continue;
                }
            }

            // =================================================================
            // RETRY
            // =================================================================
            if (decision == MediationDecision.RETRY) {

                if (state.getCurrentPhase() == RepairPhase.REPRODUCE
                        && state.getRepairState() == RepairState.FAILURE_REPRODUCED
                        && executionResult.getTestResults().wereRun()
                        && executionResult.getTestExitCode() == 0) {
                    log.error("[Orchestrator] Failure no longer reproducible after REPLAN -> ABORTED");
                    state.getLifecycle().transitionTo(RepairState.ABORTED);
                    log.info("[FSM FINAL STATE] {}", state.getRepairState());
                    logBenchmark(state, goal, startTime, false,
                            "Failure not reproducible after workspace restore");
                    return failResult(state, "Failure no longer reproducible after REPLAN");
                }

                if (state.getCurrentPhase() == RepairPhase.REPRODUCE
                        && state.isStructureDiscovered()
                        && !executionResult.getTestResults().wereRun()) {
                    log.info("[Orchestrator] REPRODUCE: structure discovered but tests not run — " +
                             "advancing to next plan step (not replaying discovery task)");
                    state.advanceToNextTask();
                } else {
                    log.info("[Orchestrator] Retrying current task.");
                }
                continue;
            }

            // =================================================================
            // REPLAN
            // =================================================================
            if (decision == MediationDecision.REPLAN) {

                log.info("[Orchestrator] Replanning: {}", mediation.getReasoning());
                state.incrementReplanCount();

                if (state.getCurrentPhase() == RepairPhase.VALIDATE
                        && state.getRepairState() == RepairState.PATCH_ATTEMPTED) {
                    log.info("[Orchestrator] VALIDATE failed (exitCode != 0) -> VALIDATED_FAILURE");
                    state.getLifecycle().transitionTo(RepairState.VALIDATED_FAILURE);
                }

                if (state.getReplanCount() >= MAX_CONSECUTIVE_REPLANS) {
                    log.error("[Orchestrator] Replan cap reached ({}) -> ABORTED", MAX_CONSECUTIVE_REPLANS);
                    state.getLifecycle().transitionTo(RepairState.ABORTED);
                    log.info("[FSM FINAL STATE] {}", state.getRepairState());
                    logBenchmark(state, goal, startTime, false, "Replan cap exceeded");
                    return failResult(state, "Replan cap exceeded — " + mediation.getReasoning());
                }

                RepairState repairStateAtReplan = state.getRepairState();
                if (repairStateAtReplan == RepairState.FAILURE_REPRODUCED
                        || repairStateAtReplan == RepairState.ANALYSIS_COMPLETE
                        || repairStateAtReplan == RepairState.VALIDATED_FAILURE) {
                    state.getLifecycle().transitionTo(RepairState.FAILURE_REPRODUCED);
                    log.info("[Orchestrator] Lifecycle: {} -> FAILURE_REPRODUCED (REPLAN cycle)",
                            repairStateAtReplan);
                } else {
                    log.info("[Orchestrator] REPLAN from repairState={} — lifecycle unchanged",
                            repairStateAtReplan);
                }

                RepairPhase phaseAtReplan = state.getCurrentPhase();
                if (phaseAtReplan == RepairPhase.REPAIR_ANALYZE
                        || phaseAtReplan == RepairPhase.REPAIR_PATCH) {
                    captureRepairAttempt(state, mediation.getReasoning());
                } else {
                    log.info("[Orchestrator] Skipping history capture — REPLAN from {} (not repair phase)",
                            phaseAtReplan);
                }

                if (workspaceSnapshot != null) {
                    List<String> modifiedFiles = state.getModifiedFiles();
                    boolean workspaceWasModified = modifiedFiles != null && !modifiedFiles.isEmpty();

                    if (workspaceWasModified) {
                        try {
                            log.info("[RESTORE DEBUG] Restoring snapshot identity={}",
                                    System.identityHashCode(workspaceSnapshot));
                            log.info("[RESTORE DEBUG] Snapshot file count={}",
                                    workspaceSnapshot.getFiles().size());
                            fileSystemManager.restoreWorkspace(workspaceSnapshot);
                            log.info("[Orchestrator] Workspace restored and snapshot reset");
                            try {
                                String restoredHash = fileSystemManager.hashEntireWorkspace(
                                        java.nio.file.Paths.get(fileSystemManager.getWorkspacePath()));
                                log.info("[WORKSPACE HASH] AFTER_RESTORE={}", restoredHash);
                                if (t0Hash != null && !t0Hash.equals(restoredHash)) {
                                    throw new IllegalStateException(
                                        "Workspace rollback invariant violated: T0 != AFTER_RESTORE");
                                }
                            } catch (IllegalStateException e) {
                                throw e;
                            } catch (Exception e) {
                                log.error("[WORKSPACE HASH] Failed to compute post-restore hash", e);
                            }
                        } catch (IllegalStateException e) {
                            throw e;
                        } catch (FileSystemManager.FileSystemException e) {
                            log.error("[Orchestrator] FATAL: Workspace restore failed. Aborting.", e);
                            logBenchmark(state, goal, startTime, false,
                                    "Workspace restore failed: " + e.getMessage());
                            return failResult(state, "Workspace restore failed: " + e.getMessage());
                        }

                        state.clearFileContentCache();
                        // [Fix 8] Do NOT call clearFailureContext() after restore.
                        // Failure context (failingArtifact, subtype, reason) must persist
                        // across REPLAN cycles so the next REPRODUCE phase can re-ground
                        // against the correct artifact. workspaceFiles (Fix 5) is also
                        // preserved — it is never cleared.
                        // structureDiscovered is reset inside clearFileContentCache().
                        state.setStructureDiscovered(false);
                        log.info("[Orchestrator] File content cache cleared after workspace restore " +
                                 "(failure context preserved, workspace index preserved)");

                        // [Fix 9] Invariant assertion: every file that was modified must exist
                        // after restore. A full snapshot (Fix 1) guarantees this — this assertion
                        // catches any regression where the snapshot predicate accidentally narrows.
                        List<String> modifiedBeforeRestore = state.getModifiedFiles();
                        for (String f : modifiedBeforeRestore) {
                            if (!fileSystemManager.fileExists(f)) {
                                throw new IllegalStateException(
                                    "Restore invariant violated: modified file '" + f +
                                    "' is missing after restore. Full snapshot should cover all files.");
                            }
                        }
                        if (!modifiedBeforeRestore.isEmpty()) {
                            log.info("[Orchestrator] Restore invariant verified: {} modified file(s) present",
                                    modifiedBeforeRestore.size());
                        }
                    } else {
                        log.info("[Orchestrator] No files modified — skipping workspace restore, " +
                                 "file cache preserved");
                    }

                    workspaceSnapshot = null;
                    log.info("[SNAPSHOT REF] workspaceSnapshot assigned. identity=null");
                } else {
                    log.warn("[Orchestrator] REPLAN triggered but no workspace snapshot exists.");
                }

                state.clearModifiedFiles();
                state.softReset();
                state.setCurrentPhase(RepairPhase.REPRODUCE);
                log.info("[Orchestrator] State reset — phase=REPRODUCE, analysis preserved={}",
                        state.getLastRootCauseAnalysis() != null);

                plan = planner.revisePlan(goal, state);
                state.updatePlan(plan);
                continue;
            }

            log.error("[Orchestrator] Unknown mediator decision: {}", decision);
            logBenchmark(state, goal, startTime, false, "Unknown mediator decision");
            return failResult(state, "Unknown mediator decision: " + decision);
        }
    }

    // =========================================================================
    // PRE-FLIGHT HELPER
    // =========================================================================

    private int runPreflightCommand(String workingDir, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new java.io.File(workingDir));
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[PreFlight] {}", line);
                }
            }

            int exit = process.waitFor();
            log.info("[PreFlight] Command {} exited with {}", java.util.Arrays.toString(command), exit);
            return exit;
        } catch (IOException | InterruptedException e) {
            log.error("[PreFlight] Command {} failed to start: {}",
                      java.util.Arrays.toString(command), e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return -1;
        }
    }

    // =========================================================================
    // FSM v1.0: PHASE-STATE PAIRING ASSERTION
    // =========================================================================

    private void assertValidPhaseState(RepairPhase phase, RepairState repairState) {

        boolean valid = switch (phase) {
            case REPRODUCE      -> repairState == RepairState.INITIAL
                                || repairState == RepairState.FAILURE_REPRODUCED;
            case REPAIR_ANALYZE -> repairState == RepairState.FAILURE_REPRODUCED;
            case REPAIR_PATCH   -> repairState == RepairState.ANALYSIS_COMPLETE;
            case VALIDATE       -> repairState == RepairState.PATCH_ATTEMPTED;
        };

        if (!valid) {
            String msg = String.format(
                "[Orchestrator] ILLEGAL PHASE-STATE PAIRING: phase=%s, repairState=%s",
                phase, repairState);
            log.error(msg);
            throw new IllegalStateException(msg);
        }
    }

    // =========================================================================
    // PLAN VALIDATION
    // =========================================================================

    private PlannerOutput generateAndValidateRepairPlan(String goal, SharedState state) {

        int validationAttempts = 0;

        while (validationAttempts < MAX_PLAN_VALIDATION_RETRIES) {

            PlannerOutput plan = planner.generatePlan(goal, state);

            boolean hasTestSteps = plan.getInvestigationSteps().stream().anyMatch(step -> {
                String lower = step.toLowerCase();
                return lower.contains("run test")
                        || lower.contains("execute test")
                        || lower.contains("reproduce")
                        || (lower.contains("test") && lower.contains("run"));
            });

            if (!hasTestSteps) {
                log.info("[Orchestrator] REPAIR plan validated — no test steps");
                return plan;
            }

            validationAttempts++;
            log.error("[Orchestrator] INVALID REPAIR PLAN (attempt {}/{}): contains test steps",
                    validationAttempts, MAX_PLAN_VALIDATION_RETRIES);

            plan.getInvestigationSteps().forEach(s -> log.error("[Orchestrator]   - {}", s));
        }

        log.error("[Orchestrator] Plan validation failed after {} attempts. Using safe repair fallback.",
                MAX_PLAN_VALIDATION_RETRIES);

        return PlannerOutput.repairFallback(goal);
    }

    // =========================================================================
    // REPAIR HISTORY CAPTURE
    // =========================================================================

    private void captureRepairAttempt(SharedState state, String mediatorReasoning) {

        int               attemptNumber = state.getReplanCount();
        RepairPhase       phase         = state.getCurrentPhase();
        String            lastToolError = state.getLastToolError();
        List<String>      modified      = state.getModifiedFiles();
        RootCauseAnalysis analysis      = state.getLastRootCauseAnalysis();

        RepairAttempt.Outcome outcome;

        if (phase == RepairPhase.REPAIR_ANALYZE) {
            outcome = (analysis != null && !analysis.isValid())
                    ? RepairAttempt.Outcome.ANALYSIS_INVALID
                    : RepairAttempt.Outcome.ANALYSIS_CAP_EXCEEDED;
        } else {
            if (lastToolError != null && lastToolError.contains("not found")) {
                outcome = RepairAttempt.Outcome.SEARCH_FAILED;
            } else if (lastToolError != null && lastToolError.contains("multiple")) {
                outcome = RepairAttempt.Outcome.SEARCH_AMBIGUOUS;
            } else if (modified == null || modified.isEmpty()) {
                outcome = RepairAttempt.Outcome.NO_PATCH;
            } else if (SharedState.SUBTYPE_SYNTAX_ERROR.equals(state.getCollectionFailureSubtype())) {
                outcome = RepairAttempt.Outcome.SYNTAX_ERROR;
            } else {
                outcome = RepairAttempt.Outcome.VALIDATE_FAILED;
            }
        }

        String patchSummary = (modified != null && !modified.isEmpty())
                ? "replace_in_file on " + String.join(", ", modified)
                : "no patch applied";

        String searchBlockUsed = null;
        if (lastToolError != null && lastToolError.contains("You searched for:")) {
            int idx = lastToolError.indexOf("You searched for:") + "You searched for:".length();
            String raw = lastToolError.substring(idx).trim();
            searchBlockUsed = raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
        }

        RepairAttempt attempt = RepairAttempt.builder(attemptNumber, outcome)
                .patchSummary(patchSummary)
                .searchBlockUsed(searchBlockUsed)
                .rootCauseSummary(analysis != null ? analysis.getRootCauseSummary() : null)
                .minimalFixStrategy(analysis != null ? analysis.getMinimalFixStrategy() : null)
                .validationFailureSubtype(state.getCollectionFailureSubtype())
                .validationFailureLine(state.getFailingArtifactLine())
                .validationFailureReason(state.getCollectionFailureReason())
                .build();

        state.addRepairAttempt(attempt);

        log.info("[Orchestrator] RepairAttempt #{} captured: phase={}, outcome={}, patch='{}'",
                attemptNumber, phase, outcome, patchSummary);
    }

    // =========================================================================
    // RESULT BUILDERS
    // =========================================================================

    private Map<String, Object> successResult(SharedState state, String reasoning) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success",         true);
        result.put("totalIterations", state.getTotalIterations());
        result.put("status",          reasoning != null ? reasoning : "All tests pass");
        result.put("details",         formatModifiedFiles(state.getModifiedFiles()));
        return result;
    }

    private Map<String, Object> failResult(SharedState state, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success",         false);
        result.put("totalIterations", state.getTotalIterations());
        result.put("status",          "FAILED");
        result.put("details",         reason != null ? reason : "");
        return result;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private String formatModifiedFiles(List<String> files) {
        if (files == null || files.isEmpty()) return "No files modified";
        return "Modified: " + String.join(", ", files);
    }

    private String extractCaseId(String goal) {
        if (goal == null) return "unknown";
        if (goal.contains("bug-")) {
            int start = goal.indexOf("bug-");
            int end   = goal.indexOf(" ", start);
            if (end == -1) end = Math.min(start + 20, goal.length());
            return goal.substring(start, end).trim();
        }
        return goal.length() > 30 ? goal.substring(0, 30) : goal;
    }

    private void logBenchmark(SharedState state, String goal, long startTime,
                               boolean success, String finalStatus) {
        long wallTime  = (System.currentTimeMillis() - startTime) / 1000;
        String caseId  = extractCaseId(goal);
        String artifact = state.getFailingArtifact() != null ? state.getFailingArtifact() : "unknown";
        String failType = state.getLastTestResults() != null
                ? state.getLastTestResults().getFailureType().toString()
                : "UNKNOWN";

        String json = String.format(
                "{\"case_id\":\"%s\",\"resolved\":%b,\"total_iterations\":%d," +
                "\"replan_count\":%d,\"tool_call_count\":%d,\"failure_type\":\"%s\"," +
                "\"failing_artifact\":\"%s\",\"wall_time_seconds\":%d,\"final_status\":\"%s\"}",
                caseId, success,
                state.getTotalIterations(), state.getReplanCount(), state.getToolCallCount(),
                failType, artifact, wallTime,
                finalStatus != null ? finalStatus.replace("\"", "'") : ""
        );

        log.info("[Benchmark] {}", json);
    }

    private void exportWorkspaceMetadata(SharedState state, int exitCode) {
        try {
            String       workspacePath = fileSystemManager.getWorkspacePath();
            List<String> modifiedFiles = state.getModifiedFiles();

            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"workspace\": \"").append(workspacePath).append("\",\n");
            json.append("  \"modified_files\": [");

            for (int i = 0; i < modifiedFiles.size(); i++) {
                json.append("\"").append(modifiedFiles.get(i)).append("\"");
                if (i < modifiedFiles.size() - 1) json.append(", ");
            }

            json.append("],\n");
            json.append("  \"iterations\": ").append(state.getTotalIterations()).append(",\n");
            json.append("  \"replans\": ").append(state.getReplanCount()).append(",\n");
            json.append("  \"tests_passed\": ").append(exitCode == 0).append(",\n");
            json.append("  \"exit_code\": ").append(exitCode).append("\n");
            json.append("}\n");

            java.nio.file.Path metadataPath =
                    java.nio.file.Paths.get(workspacePath, "synapsenet_metadata.json");
            java.nio.file.Files.writeString(metadataPath, json.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);

            log.info("[Orchestrator] Exported workspace metadata to {}", metadataPath);

        } catch (Exception e) {
            log.error("[Orchestrator] Failed to export workspace metadata", e);
        }
    }
}