package com.synapsenet.core.mediator;

import java.time.Instant;

/**
 * MediationResult — immutable value object returned by MediatorAgent.decide().
 *
 * Always construct via static factory methods. The public constructor is not
 * private only to satisfy potential deserialization — prefer the factories.
 *
 * Static factories (the full API used by MediatorAgent):
 *   MediationResult.success(reasoning, iterations)
 *   MediationResult.fail(reasoning, iterations)
 *   MediationResult.advance(reasoning, iterations)
 *   MediationResult.retry(reasoning, iterations)
 *   MediationResult.replan(reasoning, iterations)
 *
 * REMOVED:
 *   isApproved()         — predates the 5-value enum; use isSuccess()
 *   getConfidenceScore() — duplicate of getConfidence(); removed
 *   taskId field         — not known at decision time; always null; removed
 */
public class MediationResult {

    private final String            mediatorAgentId;
    private final MediationDecision decision;
    private final String            reasoning;
    private final double            confidence;
    private final int               iterationCount;
    private final Instant           createdAt;

    // =========================================================================
    // Constructor
    // =========================================================================

    public MediationResult(
            String            mediatorAgentId,
            MediationDecision decision,
            String            reasoning,
            double            confidence,
            int               iterationCount
    ) {
        this.mediatorAgentId = mediatorAgentId;
        this.decision        = decision;
        this.reasoning       = reasoning != null ? reasoning : "";
        this.confidence      = confidence;
        this.iterationCount  = iterationCount;
        this.createdAt       = Instant.now();
    }

    // =========================================================================
    // Static factory methods — primary API
    // =========================================================================

    /** All tests pass. Terminal success. */
    public static MediationResult success(String reasoning, int iterations) {
        return new MediationResult("mediator-agent-1", MediationDecision.SUCCESS,
                reasoning, 1.0, iterations);
    }

    /** Unrecoverable failure. Terminal. */
    public static MediationResult fail(String reasoning, int iterations) {
        return new MediationResult("mediator-agent-1", MediationDecision.FAIL,
                reasoning, 1.0, iterations);
    }

    /** Current phase complete — advance to next RepairPhase. */
    public static MediationResult advance(String reasoning, int iterations) {
        return new MediationResult("mediator-agent-1", MediationDecision.ADVANCE,
                reasoning, 0.9, iterations);
    }

    /** Transient failure — retry same task, same phase. */
    public static MediationResult retry(String reasoning, int iterations) {
        return new MediationResult("mediator-agent-1", MediationDecision.RETRY,
                reasoning, 0.7, iterations);
    }

    /** Strategy exhausted — restore snapshot, reset to REPRODUCE, revisePlan. */
    public static MediationResult replan(String reasoning, int iterations) {
        return new MediationResult("mediator-agent-1", MediationDecision.REPLAN,
                reasoning, 0.8, iterations);
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public String            getMediatorAgentId() { return mediatorAgentId; }
    public MediationDecision getDecision()        { return decision; }
    public String            getReasoning()       { return reasoning; }
    public double            getConfidence()      { return confidence; }
    public int               getIterationCount()  { return iterationCount; }
    public Instant           getCreatedAt()       { return createdAt; }

    // Convenience predicates
    public boolean isSuccess() { return decision == MediationDecision.SUCCESS; }
    public boolean isFail()    { return decision == MediationDecision.FAIL;    }
    public boolean isAdvance() { return decision == MediationDecision.ADVANCE; }
    public boolean isRetry()   { return decision == MediationDecision.RETRY;   }
    public boolean isReplan()  { return decision == MediationDecision.REPLAN;  }

    // =========================================================================
    // Display
    // =========================================================================

    @Override
    public String toString() {
        String preview = reasoning.length() > 80 ? reasoning.substring(0, 80) + "..." : reasoning;
        return String.format("MediationResult{decision=%s, confidence=%.2f, iter=%d, reasoning='%s'}",
                decision, confidence, iterationCount, preview);
    }
}