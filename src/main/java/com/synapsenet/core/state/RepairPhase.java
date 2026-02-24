package com.synapsenet.core.state;

/**
 * Explicit phase graph for SynapseNet's repair state machine.
 *
 * REPRODUCE → REPAIR_ANALYZE → REPAIR_PATCH → VALIDATE
 *
 * REPRODUCE      — Run tests to confirm the failure exists. Discover project structure.
 *                  No file modifications allowed.
 *
 * REPAIR_ANALYZE — LLM performs root-cause diagnosis. ALL tool calls are structurally
 *                  blocked. LLM returns raw JSON (no tool_calls wrapper) parsed into a
 *                  RootCauseAnalysis object stored in SharedState.
 *                  Advances only when analysis passes deterministic structural validation:
 *                    - All required fields non-empty
 *                    - artifactPath matches state.getFailingArtifact()
 *                    - artifactLine within ±50 lines of state.getFailingArtifactLine()
 *                  Retry on invalid analysis. Retry cap → REPLAN.
 *
 * REPAIR_PATCH   — LLM applies a patch grounded in lastRootCauseAnalysis.
 *                  Must produce a file modification (hasPatch == true) to advance.
 *                  Tool-error escalation ladder applies unchanged.
 *                  Retry cap → REPLAN.
 *
 * VALIDATE       — Run tests only. Confirm the patch fixed the bug.
 *                  Pass → SUCCESS. Fail → REPLAN.
 *
 * NOTE: The previous REPAIR phase has been fully renamed to REPAIR_PATCH.
 * No legacy REPAIR alias exists anywhere in this codebase.
 */
public enum RepairPhase {
    REPRODUCE,
    REPAIR_ANALYZE,
    REPAIR_PATCH,
    VALIDATE
}