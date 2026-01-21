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
public class PlannerAgent implements Agent, SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(PlannerAgent.class);

    private final TaskService taskService;
    private final EventBus eventBus;

    public PlannerAgent(TaskService taskService, EventBus eventBus) {
        this.taskService = taskService;
        this.eventBus = eventBus;
    }

    @Override
    public String getAgentId() {
        return "planner-agent-1";
    }

    @Override
    public AgentType getAgentType() {
        return AgentType.PLANNER;
    }

    @Override
    public void handleTask(String taskId) {
        log.info("[PlannerAgent] Planning task {}", taskId);

        // Existing behavior
        taskService.assignTask(taskId);

        // DAY 7 ADDITION: produce result
        AgentResult result = new AgentResult(
                taskId,
                getAgentType(),
                getAgentId(),
                "Task planned and assigned"
        );

        eventBus.publish(new Event(
                EventType.AGENT_RESULT_PRODUCED,
                getAgentId(),
                result
        ));
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.TASK_CREATED) {
            String taskId = event.getPayload().toString();
            handleTask(taskId);
        }
    }
}
