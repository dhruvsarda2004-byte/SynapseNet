package com.synapsenet.core.critic;

import java.time.Instant;
import java.util.List;

public class CriticFeedback {

    private final String taskId;
    private final String criticAgentId;
    private final List<String> issues;
    private final List<String> suggestions;
    private final RiskLevel riskLevel;
    private final double confidenceScore;
    private final String summary;
    private final Instant createdAt;

    public CriticFeedback(
            String taskId,
            String criticAgentId,
            List<String> issues,
            List<String> suggestions,
            RiskLevel riskLevel,
            double confidenceScore,
            String summary
    ) {
        this.taskId = taskId;
        this.criticAgentId = criticAgentId;
        this.issues = issues;
        this.suggestions = suggestions;
        this.riskLevel = riskLevel;
        this.confidenceScore = confidenceScore;
        this.summary = summary;
        this.createdAt = Instant.now();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getCriticAgentId() {
        return criticAgentId;
    }

    public List<String> getIssues() {
        return issues;
    }

    public List<String> getSuggestions() {
        return suggestions; 
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public String getSummary() {
        return summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
