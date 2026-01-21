package com.synapsenet.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.communication.EventBus;
import com.synapsenet.communication.SynapseEventListener;
import com.synapsenet.core.event.Event;
import com.synapsenet.core.event.EventType;
import com.synapsenet.core.task.TaskService;

@Component
public class ExecutorAgent implements Agent, SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(ExecutorAgent.class);

    private final TaskService taskService;
    private final EventBus eventBus;

    public ExecutorAgent(TaskService taskService, EventBus eventBus) {
        this.taskService = taskService;
        this.eventBus = eventBus;
    }

    @Override
    public String getAgentId() {
        return "executor-agent-1";
    }

    @Override
    public AgentType getAgentType() {
        return AgentType.EXECUTOR;
    }

    @Override
    public void handleTask(String taskId) {
        log.info("[ExecutorAgent] Executing task {}", taskId);

        // Existing behavior
        taskService.completeTask(taskId);

        // DAY 7 ADDITION: produce result
        AgentResult result = new AgentResult(
                taskId,
                getAgentType(),
                getAgentId(),
                "Task executed successfully"
        );

        eventBus.publish(new Event(
                EventType.AGENT_RESULT_PRODUCED,
                getAgentId(),
                result
        ));
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.TASK_ASSIGNED) {
            String taskId = event.getPayload().toString();
            handleTask(taskId);
        }
    }
}
