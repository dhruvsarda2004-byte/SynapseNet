package com.synapsenet.core.task;

import java.time.Instant;
import java.util.UUID;

public class Task {

    private final String taskId;  // id can never change hence final 
    private final String description; // description should not change either so its also final 
    // as id and description are final we can only use getter function with them and not the setter function 
    private TaskStatus status;
    private final Instant createdAt;

    public Task(String description) {
        this.taskId = UUID.randomUUID().toString();
        this.description = description;
        this.status = TaskStatus.CREATED;
        this.createdAt = Instant.now();
    }

    public String getTaskId() {
        return taskId;
    }

    public String getDescription() {
        return description;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
