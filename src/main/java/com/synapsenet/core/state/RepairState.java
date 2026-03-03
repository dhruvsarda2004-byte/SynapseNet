package com.synapsenet.core.state;

/**
 * RepairState — deterministic FSM states for SynapseNet's repair lifecycle.
 *
 * Runs in parallel to RepairPhase (which controls what the Executor does).
 * RepairState controls WHERE in the repair process SynapseNet stands.
 *
 * TRANSITION AUTHORITY: RepairLifecycle.transitionTo() — structural enforcement.
 * MUTATION ACTOR:       SimpleTaskOrchestrator — the ONLY class allowed to call transitionTo().
 * READ ACCESS:          All agents — via state.getRepairState() (read-only).
 *
 * GROUND TRUTH INVARIANT:
 *   SUCCESS may ONLY be emitted when state == VALIDATED_SUCCESS.
 *   If exitCode != 0, VALIDATED_SUCCESS is unreachable. SUCCESS is therefore impossible.
 *
 * Terminal states: VALIDATED_SUCCESS, NO_FAILURE_FOUND, ABORTED.
 * Illegal transitions throw IllegalStateException immediately — no silent failures.
 */
public enum RepairState {

    /**
     * Starting state. Tests have not yet been run.
     *
     * Allowed transitions:
     *   exitCode != 0          → FAILURE_REPRODUCED
     *   exitCode == 0          → NO_FAILURE_FOUND
     *   infrastructure failure → ABORTED
     */
    INITIAL,

    /**
     * A test failure has been confirmed by the REPRODUCE phase.
     *
     * Allowed transitions:
     *   analysis completed     → ANALYSIS_COMPLETE
     *   replan (retry cycle)   → FAILURE_REPRODUCED  (self-loop)
     *   infrastructure failure → ABORTED
     */
    FAILURE_REPRODUCED,

    /**
     * Root cause analysis has been validated and stored in SharedState.
     *
     * Allowed transitions:
     *   patch applied          → PATCH_ATTEMPTED
     *   replan                 → FAILURE_REPRODUCED
     *   infrastructure failure → ABORTED
     */
    ANALYSIS_COMPLETE,

    /**
     * A patch has been applied to the workspace.
     *
     * Allowed transitions:
     *   VALIDATE exitCode == 0 → VALIDATED_SUCCESS
     *   VALIDATE exitCode != 0 → VALIDATED_FAILURE
     *   infrastructure failure → ABORTED
     */
    PATCH_ATTEMPTED,

    /**
     * Validation confirmed the patch did NOT fix the bug.
     *
     * Allowed transitions:
     *   replanCount < MAX      → FAILURE_REPRODUCED
     *   replanCount >= MAX     → ABORTED
     */
    VALIDATED_FAILURE,

    /**
     * TERMINAL — validation confirmed the patch fixed the bug.
     * SUCCESS may only be emitted when this state is reached.
     * No outbound transitions.
     */
    VALIDATED_SUCCESS,

    /**
     * TERMINAL — tests passed on first run, no failure to fix.
     * SUCCESS emitted immediately (no repair needed).
     * No outbound transitions.
     */
    NO_FAILURE_FOUND,

    /**
     * TERMINAL — infrastructure failure, replan cap exceeded, or illegal state detected.
     * Run abandoned with FAIL result.
     * No outbound transitions.
     */
    ABORTED
}