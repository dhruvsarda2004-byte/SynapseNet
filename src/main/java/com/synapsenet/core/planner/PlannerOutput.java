package com.synapsenet.core.planner;

import java.time.Instant;

public class PlannerOutput {

    private final String taskId;
    private final String plannerAgentId;
    private final String originalTask;
    private final String planText;
    private final Instant createdAt;

    public PlannerOutput(
            String taskId,
            String plannerAgentId,
            String originalTask,
            String planText
    ) {
        this.taskId = taskId;
        this.plannerAgentId = plannerAgentId;
        this.originalTask = originalTask;
        this.planText = planText;
        this.createdAt = Instant.now();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getPlannerAgentId() {
        return plannerAgentId;
    }

    public String getOriginalTask() {
        return originalTask;
    }

    public String getPlanText() {
        return planText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
