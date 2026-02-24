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
import com.synapsenet.core.filesystem.FileSystemManager;
import com.synapsenet.core.mediator.MediationDecision;
import com.synapsenet.core.mediator.MediationResult;
import com.synapsenet.core.planner.PlannerOutput;
import com.synapsenet.core.state.SharedState;
import com.synapsenet.core.state.RepairPhase;
import com.synapsenet.core.state.RepairAttempt;
import com.synapsenet.core.state.RootCauseAnalysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SimpleTaskOrchestrator — top-level controller for the Debugger CIR loop.
 *
 * Phase flow:  REPRODUCE → REPAIR_ANALYZE → REPAIR_PATCH → VALIDATE
 *
 * Returns Map<String, Object> — no external DTO needed.
 * Keys: "success" (Boolean), "totalIterations" (Integer),
 *       "status" (String), "details" (String)
 *
 * Benchmark data is logged inline via logBenchmark() — no BenchmarkResult DTO.
 *
 * CIR REMOVAL CHECKLIST (nothing below should reference these):
 *   ✗ CIRResult
 *   ✗ BenchmarkResult
 *   ✗ com.synapsenet.orchestrator.dto.*
 */
@Component
public class SimpleTaskOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SimpleTaskOrchestrator.class);

    private final PlannerAgent      planner;
    private final ExecutorAgent     executor;
    private final CriticAgent       critic;
    private final MediatorAgent     mediator;
    private final FileSystemManager fileSystemManager;

    private static final int MAX_CONSECUTIVE_REPLANS     = 3;
    private static final int MAX_PLAN_VALIDATION_RETRIES = 2;

    private FileSystemManager.WorkspaceSnapshot workspaceSnapshot = null;

    public SimpleTaskOrchestrator(
            PlannerAgent      planner,
            ExecutorAgent     executor,
            CriticAgent       critic,
            MediatorAgent     mediator,
            FileSystemManager fileSystemManager
    ) {
        this.planner           = planner;
        this.executor          = executor;
        this.critic            = critic;
        this.mediator          = mediator;
        this.fileSystemManager = fileSystemManager;
    }

    // =========================================================================
    // MAIN ENTRY POINT
    // =========================================================================

    /**
     * Run the full Debugger loop for the given goal.
     *
     * @param goal  Natural-language description of the bug to fix (e.g. SWE-bench task string)
     * @return      Result map with keys: success, totalIterations, status, details
     */
    public Map<String, Object> runTask(String goal) {

        log.info("========== SYNAPSENET LOOP START ==========");
        long startTime = System.currentTimeMillis();
        workspaceSnapshot = null;

        SharedState state              = new SharedState(goal);
        int         consecutiveReplans = 0;

        PlannerOutput plan = planner.generatePlan(goal, state);
        state.updatePlan(plan);

        while (true) {

            state.incrementTotalIterations();
            String currentTask = state.getCurrentTask();

            // -----------------------------------------------------------------
            // NULL TASK — planner failed to produce a valid step
            // -----------------------------------------------------------------
            if (currentTask == null) {

                log.warn("[Orchestrator] No current task. Triggering replan.");
                consecutiveReplans++;

                if (consecutiveReplans >= MAX_CONSECUTIVE_REPLANS) {
                    log.error("[Orchestrator] Planner failed {} times. Aborting.", MAX_CONSECUTIVE_REPLANS);
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

            // -----------------------------------------------------------------
            // EXECUTE → CRITIQUE → DECIDE
            // -----------------------------------------------------------------

            ExecutionResult executionResult = executor.execute(currentTask, state);

            if (executionResult.getTestResults() != null
                    && executionResult.getTestResults().wereRun()) {
                state.setLastTestResults(executionResult.getTestResults());
                log.info("[Orchestrator] Updated test results: {}",
                        executionResult.getTestResults().getSummary());
            }

            state.recordExecution(executionResult);

            CriticFeedback critique = critic.analyze(goal, executionResult, state);
            state.recordCritique(critique);

            MediationResult mediation = mediator.decide(executionResult, critique, state);
            log.info("[Orchestrator] Mediator: {}", mediation);

            MediationDecision decision = mediation.getDecision();

            // =================================================================
            // SUCCESS
            // =================================================================
            if (decision == MediationDecision.SUCCESS) {

                log.info("========== SYNAPSENET SUCCESS ==========");
                exportWorkspaceMetadata(state, 0);
                logBenchmark(state, goal, startTime, true, mediation.getReasoning());

                return successResult(state, mediation.getReasoning());
            }

            // =================================================================
            // FAIL
            // =================================================================
            if (decision == MediationDecision.FAIL) {

                log.warn("========== SYNAPSENET FAILED ==========");
                exportWorkspaceMetadata(state, 1);
                logBenchmark(state, goal, startTime, false, mediation.getReasoning());

                return failResult(state, mediation.getReasoning());
            }

            // =================================================================
            // ADVANCE
            // =================================================================
            if (decision == MediationDecision.ADVANCE) {

                RepairPhase phase = state.getCurrentPhase();

                // REPRODUCE → REPAIR_ANALYZE
                if (phase == RepairPhase.REPRODUCE) {

                    log.info("[Orchestrator] Transition: REPRODUCE → REPAIR_ANALYZE");
                    state.setCurrentPhase(RepairPhase.REPAIR_ANALYZE);

                    if (workspaceSnapshot == null) {
                        try {
                            String failingArtifact = state.getFailingArtifact();
                            workspaceSnapshot = fileSystemManager.snapshotWorkspace(p -> {
                                String path           = p.toString().replace('\\', '/');
                                boolean isUnderSrc    = path.startsWith("src/") && path.endsWith(".py");
                                boolean isUnderPytest = path.startsWith("_pytest/") && path.endsWith(".py");
                                boolean isFailingArt  = failingArtifact != null &&
                                        path.replace('\\', '/').endsWith(failingArtifact.replace('\\', '/'));
                                return isUnderSrc || isUnderPytest || isFailingArt;
                            });
                            log.info("[Orchestrator] Snapshot taken ({} files, artifact: {})",
                                    workspaceSnapshot.size(), failingArtifact);
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

                // REPAIR_ANALYZE → REPAIR_PATCH
                if (phase == RepairPhase.REPAIR_ANALYZE) {

                    log.info("[Orchestrator] Transition: REPAIR_ANALYZE → REPAIR_PATCH");
                    state.setCurrentPhase(RepairPhase.REPAIR_PATCH);

                    PlannerOutput patchPlan = generateAndValidateRepairPlan(goal, state);
                    state.updatePlan(patchPlan);
                    continue;
                }

                // REPAIR_PATCH → VALIDATE
                if (phase == RepairPhase.REPAIR_PATCH) {

                    log.info("[Orchestrator] Transition: REPAIR_PATCH → VALIDATE");
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
                log.info("[Orchestrator] Retrying current task.");
                continue;
            }

            // =================================================================
            // REPLAN
            // =================================================================
            if (decision == MediationDecision.REPLAN) {

                log.info("[Orchestrator] Replanning: {}", mediation.getReasoning());
                state.incrementReplanCount();

                // Capture repair attempt only when REPLAN fires from a repair phase
                RepairPhase phaseAtReplan = state.getCurrentPhase();
                if (phaseAtReplan == RepairPhase.REPAIR_ANALYZE
                        || phaseAtReplan == RepairPhase.REPAIR_PATCH) {
                    captureRepairAttempt(state, mediation.getReasoning());
                } else {
                    log.info("[Orchestrator] Skipping history capture — REPLAN from {} (not repair phase)",
                            phaseAtReplan);
                }

                if (workspaceSnapshot != null) {
                    try {
                        fileSystemManager.restoreWorkspace(workspaceSnapshot);
                        workspaceSnapshot = null;
                        log.info("[Orchestrator] Workspace restored and snapshot reset");
                    } catch (FileSystemManager.FileSystemException e) {
                        log.error("[Orchestrator] FATAL: Workspace restore failed. Aborting.", e);
                        logBenchmark(state, goal, startTime, false,
                                "Workspace restore failed: " + e.getMessage());
                        return failResult(state, "Workspace restore failed: " + e.getMessage());
                    }
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

            // =================================================================
            // SAFETY FALLBACK — should never reach here
            // =================================================================
            log.error("[Orchestrator] Unknown mediator decision: {}", decision);
            logBenchmark(state, goal, startTime, false, "Unknown mediator decision");
            return failResult(state, "Unknown mediator decision: " + decision);
        }
    }

    // =========================================================================
    // PLAN VALIDATION
    // =========================================================================

    /**
     * Generate and validate a REPAIR_PATCH plan.
     *
     * Rejects any plan that contains test-running steps (prohibited in REPAIR_PATCH).
     * Falls back to PlannerOutput.repairFallback() after MAX_PLAN_VALIDATION_RETRIES.
     *
     * The fallback is constructed directly (not via fromJson) to guarantee the
     * no-test-steps invariant regardless of LLM output.
     */
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

    /**
     * Build and record a RepairAttempt from current state at the moment REPLAN fires.
     *
     * PRECONDITIONS:
     *   - Current phase is REPAIR_ANALYZE or REPAIR_PATCH
     *   - Called BEFORE softReset() clears lastToolError, collectionFailureSubtype, etc.
     *   - Called AFTER state.incrementReplanCount()
     */
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
            // REPAIR_PATCH
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

    /**
     * Inline benchmark logging — no external DTO.
     * Produces a single JSON line consumed by SWE-bench adapter and log parsers.
     */
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

    /**
     * Write synapsenet_metadata.json to the workspace root for the SWE-bench adapter.
     *
     * @param exitCode 0 = success, 1 = failure
     */
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