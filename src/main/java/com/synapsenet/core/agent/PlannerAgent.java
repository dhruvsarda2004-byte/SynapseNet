package com.synapsenet.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.communication.EventBus;
import com.synapsenet.communication.SynapseEventListener;
import com.synapsenet.core.event.Event;
import com.synapsenet.core.event.EventType;
import com.synapsenet.core.task.TaskService;
import com.synapsenet.llm.LLMClient;

@Component
public class PlannerAgent implements Agent, SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(PlannerAgent.class);

    private final TaskService taskService;
    private final EventBus eventBus;
    private final LLMClient llmClient;

    public PlannerAgent(TaskService taskService, EventBus eventBus ,  LLMClient llmClient) {
        this.taskService = taskService;
        this.eventBus = eventBus;
        this.llmClient = llmClient;
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

        String prompt = buildPlanningPrompt(taskId);
        String plan = llmClient.generate(prompt); 
        

        // You may keep this if it’s part of orchestration
        taskService.assignTask(taskId);

        AgentResult result = new AgentResult(
                taskId,
                getAgentType(),
                getAgentId(),
                plan
        );

        eventBus.publish(new Event(
                EventType.AGENT_RESULT_PRODUCED, // as we see agent result produced it is then stored in the store 
                getAgentId(),
                result
        ));
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.TASK_CREATED) {
            String taskId = event.getPayload().toString();
            handleTask(taskId);  // handletask only when event type = task created 
        } 
    }
    
    private String buildPlanningPrompt(String taskId) {
        return """
        You are a planning agent.
        Given the following task ID, produce a clear step-by-step plan.

        Task ID:
        %s
        """.formatted(taskId);  // formatted is used to replace %s 
    }

}
