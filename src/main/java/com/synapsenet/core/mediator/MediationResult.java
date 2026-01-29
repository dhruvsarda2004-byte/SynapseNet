package com.synapsenet.core.mediator;

import java.time.Instant;

public class MediationResult {

    private final String taskId;
    private final String mediatorAgentId;
    private final MediationDecision decision;
    private final String reasoning;
    private final double confidence;
    private final Instant createdAt;

    public MediationResult(
            String taskId,
            String mediatorAgentId,
            MediationDecision decision,
            String reasoning,
            double confidence
    ) {
        this.taskId = taskId;
        this.mediatorAgentId = mediatorAgentId;
        this.decision = decision;
        this.reasoning = reasoning;
        this.confidence = confidence;
        this.createdAt = Instant.now();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMediatorAgentId() {
        return mediatorAgentId;
    }

    public MediationDecision getDecision() {
        return decision;
    }

    public String getReasoning() {
        return reasoning;
    }

    public double getConfidence() {
        return confidence;
    }

    // ✅ Added for CIR clarity & orchestrator compatibility
    public double getConfidenceScore() {
        return confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public boolean isApproved() {
        return decision == MediationDecision.APPROVE_EXECUTION;
    }

}
