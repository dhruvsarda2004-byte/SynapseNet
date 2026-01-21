package com.synapsenet.core.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.synapsenet.communication.EventBus;
import com.synapsenet.core.event.Event;
import com.synapsenet.core.event.EventType;

@Service
public class TaskService {

    private static final Logger log =
            LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final EventBus eventBus;

    public TaskService(TaskRepository taskRepository, EventBus eventBus) {
        this.taskRepository = taskRepository;
        this.eventBus = eventBus;
    }

    // Create a new task
    public Task createTask(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Task description cannot be empty");
        }

        Task task = new Task(description);
        taskRepository.save(task);

        log.info("[TaskService] Created task {}", task.getTaskId());

        eventBus.publish(new Event(  // a new event has been published 
                EventType.TASK_CREATED, // event type 
                "TaskService", // source 
                task.getTaskId() // payload 
        ));

        return task;
    }

    // Assign task
    public void assignTask(String taskId) {
        Task task = taskRepository.findById(taskId);  // fetch that task from repository 

        if (task == null) {
            log.warn("[TaskService] Cannot assign task {} – task not found", taskId);
            return;
        }

        if (task.getStatus() != TaskStatus.CREATED) {
            log.warn("[TaskService] Cannot assign task {} – invalid state {}", 
                    taskId, task.getStatus());
            return;
        }

        taskRepository.updateStatus(taskId, TaskStatus.ASSIGNED);
        log.info("[TaskService] Assigned task {}", taskId);

        eventBus.publish(new Event(
                EventType.TASK_ASSIGNED,
                "TaskService",
                taskId
        ));
    }

    // Complete task
    public void completeTask(String taskId) {
        Task task = taskRepository.findById(taskId);

        if (task == null) {
            log.warn("[TaskService] Cannot complete task {} – task not found", taskId);
            return;
        }

        if (task.getStatus() != TaskStatus.ASSIGNED) {
            log.warn("[TaskService] Cannot complete task {} – invalid state {}", 
                    taskId, task.getStatus());
            return;
        }

        taskRepository.updateStatus(taskId, TaskStatus.COMPLETED);
        log.info("[TaskService] Completed task {}", taskId);

        eventBus.publish(new Event(
                EventType.TASK_COMPLETED,
                "TaskService",
                taskId
        ));
    }
}
