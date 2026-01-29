package com.synapsenet.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.communication.EventBus;
import com.synapsenet.communication.SynapseEventListener;
import com.synapsenet.config.ExecutionModeResolver;
import com.synapsenet.core.event.Event;
import com.synapsenet.core.event.EventType;
import com.synapsenet.core.planner.PlannerOutput;
import com.synapsenet.core.task.TaskService;
import com.synapsenet.llm.LLMClient;

@Component
public class PlannerAgent implements Agent, SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(PlannerAgent.class);

    private final TaskService taskService;
    private final EventBus eventBus;
    private final LLMClient llmClient;
    private final ExecutionModeResolver modeResolver;

    public PlannerAgent(
            TaskService taskService,
            EventBus eventBus,
            LLMClient llmClient,
            ExecutionModeResolver modeResolver
    ) {
        this.taskService = taskService;
        this.eventBus = eventBus;
        this.llmClient = llmClient;
        this.modeResolver = modeResolver;
    }

    @Override
    public String getAgentId() {
        return "planner-agent-1";
    }

    @Override
    public AgentType getAgentType() {
        return AgentType.PLANNER;
    }

    /**
     * =========================
     * CIR ENTRY POINT
     * =========================
     *
     * Used ONLY by the CIR orchestrator.
     * No events are published here.
     */
    public PlannerOutput generatePlan(
            String taskId,
            PlannerOutput previousPlan
    ) {
        log.info(
            "[PlannerAgent][CIR] Generating plan for task {}",
            taskId
        );

        String prompt = buildCIRPlanningPrompt(taskId, previousPlan);
        String planText = llmClient.generate(prompt);

        return new PlannerOutput(
                taskId,
                getAgentId(),
                taskId, // original task (can evolve later)
                planText
        );
    }

    /**
     * =========================
     * EIR ENTRY POINT
     * =========================
     *
     * Event-driven planner behavior.
     * Used in production mode.
     */
    @Override
    public void handleTask(String taskId) {

        if (modeResolver.isCIR()) {
            // CIR ignores event-driven execution
            return;
        }

        log.info("[PlannerAgent][EIR] Planning task {}", taskId);

        taskService.assignTask(taskId);

        String prompt = buildEIRPlanningPrompt(taskId);
        String planText = llmClient.generate(prompt);

        PlannerOutput plannerOutput = new PlannerOutput(
                taskId,
                getAgentId(),
                taskId,
                planText
        );

        eventBus.publish(
                new Event(
                        EventType.PLAN_PRODUCED,
                        getAgentId(),
                        plannerOutput
                )
        );

        AgentResult result = new AgentResult(
                taskId,
                getAgentType(),
                getAgentId(),
                planText
        );

        eventBus.publish(
                new Event(
                        EventType.AGENT_RESULT_PRODUCED,
                        getAgentId(),
                        result
                )
        );
    }

    /**
     * Event listener for EIR mode only.
     */
    @Override
    public void onEvent(Event event) {

        if (modeResolver.isCIR()) {
            return;
        }

        if (event.getType() == EventType.TASK_CREATED) {
            String taskId = event.getPayload().toString();
            handleTask(taskId);
        }
    }

    // =========================
    // PROMPT BUILDERS
    // =========================

    /**
     * CIR prompt supports iterative refinement.
     */
    private String buildCIRPlanningPrompt(
            String taskId,
            PlannerOutput previousPlan
    ) {
        if (previousPlan == null) {
            return """
            You are a planning agent.

            Task:
            %s

            Produce a clear, step-by-step plan.
            """.formatted(taskId);
        }

        return """
        You are refining an earlier plan.

        Task:
        %s

        Previous Plan:
        %s

        Improve the plan by fixing issues and adding missing details.
        """.formatted(
                taskId,
                previousPlan.getPlanText()
        );
    }

    /**
     * EIR prompt (single-pass planning).
     */
    private String buildEIRPlanningPrompt(String taskId) {
        return """
        You are a planning agent.

        Given the following task ID, produce a clear step-by-step plan.

        Task ID:
        %s
        """.formatted(taskId);
    }
}
