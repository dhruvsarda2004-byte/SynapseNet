package com.synapsenet.core.task;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class TaskRepository {

    private Map<String, Task> tasks = new HashMap<>();

    public void save(Task task) {
        tasks.put(task.getTaskId(), task);
    }

    public Task findById(String taskId) {
        return tasks.get(taskId);
    }

    public void updateStatus(String taskId, TaskStatus status) {
        Task task = tasks.get(taskId);
        if (task != null) {
            task.setStatus(status);
        }
    }
}
