package com.synapsenet.core.mediator;

/**
 * MediationDecision — exhaustive set of state-transition decisions for the
 * Debugger phase state machine.
 *
 * Phase graph:  REPRODUCE → REPAIR_ANALYZE → REPAIR_PATCH → VALIDATE
 *
 * Decision semantics (enforced by SimpleTaskOrchestrator):
 *
 *   SUCCESS  — All tests pass. Terminal. Orchestrator exits the loop successfully.
 *
 *   FAIL     — Unrecoverable (iteration cap exceeded or fatal infrastructure error).
 *              Terminal. Orchestrator exits the loop with failure.
 *
 *   ADVANCE  — Current phase complete. Orchestrator transitions to the next RepairPhase:
 *                REPRODUCE      → REPAIR_ANALYZE
 *                REPAIR_ANALYZE → REPAIR_PATCH
 *                REPAIR_PATCH   → VALIDATE
 *              VALIDATE + passing tests produces SUCCESS, not ADVANCE.
 *
 *   RETRY    — Transient failure. Orchestrator re-runs the same task in the same phase
 *              without resetting any state. Subject to MAX_RETRIES_PER_TASK cap.
 *
 *   REPLAN   — Strategy exhausted. Orchestrator:
 *                1. Captures RepairAttempt into history (if in a repair phase)
 *                2. Restores workspace snapshot
 *                3. Calls softReset() + clearModifiedFiles()
 *                4. Resets phase to REPRODUCE
 *                5. Calls planner.revisePlan()
 *
 * REMOVED (pre-refactor names — must not reappear anywhere):
 *   APPROVE_EXECUTION  → was ADVANCE
 *   REJECT_EXECUTION   → was FAIL
 *   REQUEST_REPLAN     → was REPLAN
 */
public enum MediationDecision {
    SUCCESS,
    FAIL,
    ADVANCE,
    RETRY,
    REPLAN
}