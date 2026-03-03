package com.synapsenet.core.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * RepairLifecycle — enforces the deterministic FSM transition rules for RepairState.
 *
 * @version FSM_v1.0_FINAL
 *
 * OWNERSHIP:
 *   Owned by SharedState. Constructed once at SharedState instantiation.
 *
 * MUTATION AUTHORITY:
 *   Only SimpleTaskOrchestrator may call transitionTo().
 *   Mediator and all other agents are read-only via getCurrentState().
 *
 * ILLEGAL TRANSITION POLICY:
 *   Any transition not in the allowed adjacency map throws IllegalStateException
 *   immediately. No silent failures, no fallbacks.
 *
 * TERMINAL STATE POLICY:
 *   VALIDATED_SUCCESS, NO_FAILURE_FOUND, ABORTED have no outbound transitions.
 *   Any transitionTo() call from a terminal state throws immediately.
 */
public class RepairLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RepairLifecycle.class);

    private RepairState currentState = RepairState.INITIAL;

    /**
     * Authoritative allowed-transition adjacency map.
     *
     * Every legal (from → to) pair is explicitly listed.
     * Any (from, to) pair NOT present here is illegal.
     */
    private static final Map<RepairState, Set<RepairState>> ALLOWED_TRANSITIONS = Map.of(
        RepairState.INITIAL, EnumSet.of(
            RepairState.FAILURE_REPRODUCED,
            RepairState.NO_FAILURE_FOUND,
            RepairState.ABORTED
        ),
        RepairState.FAILURE_REPRODUCED, EnumSet.of(
            RepairState.ANALYSIS_COMPLETE,
            RepairState.FAILURE_REPRODUCED,   // replan self-loop
            RepairState.ABORTED
        ),
        RepairState.ANALYSIS_COMPLETE, EnumSet.of(
            RepairState.PATCH_ATTEMPTED,
            RepairState.FAILURE_REPRODUCED,   // replan
            RepairState.ABORTED
        ),
        RepairState.PATCH_ATTEMPTED, EnumSet.of(
            RepairState.VALIDATED_SUCCESS,
            RepairState.VALIDATED_FAILURE,
            RepairState.ABORTED
        ),
        RepairState.VALIDATED_FAILURE, EnumSet.of(
            RepairState.FAILURE_REPRODUCED,   // retry cycle
            RepairState.ABORTED
        ),
        // Terminal states — empty sets, all transitions illegal
        RepairState.VALIDATED_SUCCESS, EnumSet.noneOf(RepairState.class),
        RepairState.NO_FAILURE_FOUND,  EnumSet.noneOf(RepairState.class),
        RepairState.ABORTED,           EnumSet.noneOf(RepairState.class)
    );

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Transition to the next state.
     *
     * Called ONLY by SimpleTaskOrchestrator.
     *
     * @param next  Target RepairState.
     * @throws IllegalStateException if the transition is not in the allowed adjacency map.
     */
    public void transitionTo(RepairState next) {
        Set<RepairState> allowed = ALLOWED_TRANSITIONS.get(currentState);

        if (allowed == null || !allowed.contains(next)) {
            String msg = String.format(
                "[RepairLifecycle] ILLEGAL TRANSITION: %s → %s (allowed from %s: %s)",
                currentState, next, currentState, allowed
            );
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        if (currentState != next) {
            log.info("[FSM] {} → {}", currentState, next);
        }
        this.currentState = next;
    }

    /**
     * Read current RepairState.
     * Safe to call from any agent — read-only, no side effects.
     */
    public RepairState getCurrentState() {
        return currentState;
    }

    /**
     * Whether the lifecycle has reached a terminal state.
     * Terminal states: VALIDATED_SUCCESS, NO_FAILURE_FOUND, ABORTED.
     */
    public boolean isTerminal() {
        return currentState == RepairState.VALIDATED_SUCCESS
            || currentState == RepairState.NO_FAILURE_FOUND
            || currentState == RepairState.ABORTED;
    }

    @Override
    public String toString() {
        return "RepairLifecycle{state=" + currentState + "}";
    }
}