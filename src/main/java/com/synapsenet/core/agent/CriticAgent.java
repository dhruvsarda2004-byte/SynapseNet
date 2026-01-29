package com.synapsenet.core.agent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.communication.EventBus;
import com.synapsenet.communication.SynapseEventListener;
import com.synapsenet.config.ExecutionModeResolver;
import com.synapsenet.core.critic.CriticFeedback;
import com.synapsenet.core.critic.RiskLevel;
import com.synapsenet.core.event.Event;
import com.synapsenet.core.event.EventType;
import com.synapsenet.core.planner.PlannerOutput;
import com.synapsenet.llm.LLMClient;

@Component
public class CriticAgent implements Agent, SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(CriticAgent.class);

    private final EventBus eventBus;
    private final LLMClient llmClient;
    private final ExecutionModeResolver modeResolver;

    public CriticAgent(
            EventBus eventBus,
            LLMClient llmClient,
            ExecutionModeResolver modeResolver
    ) {
        this.eventBus = eventBus;
        this.llmClient = llmClient;
        this.modeResolver = modeResolver;
    }

    @Override
    public String getAgentId() {
        return "critic-agent-1";
    }

    @Override
    public AgentType getAgentType() {
        return AgentType.CRITIC;
    }

    /**
     * =========================
     * CIR ENTRY POINT
     * =========================
     *
     * Used ONLY by the CIR orchestrator.
     * No events are published here.
     */
    public CriticFeedback critique(PlannerOutput plannerOutput) {

        log.info(
            "[CriticAgent][CIR] Critiquing plan for task {}",
            plannerOutput.getTaskId()
        );

        String prompt = buildCriticPrompt(plannerOutput);
        String critiqueText = llmClient.generate(prompt);

        // TEMP (paper-safe baseline scoring)
        return new CriticFeedback(
                plannerOutput.getTaskId(),
                getAgentId(),
                List.of("Potential logical gaps detected"),
                List.of("Consider validating assumptions"),
                RiskLevel.MEDIUM,
                0.7, // σ (satisfaction score)
                critiqueText
        );
    }

    /**
     * =========================
     * EIR ENTRY POINT
     * =========================
     *
     * Event-driven critic behavior.
     * Used in production mode.
     */
    public void handlePlan(PlannerOutput plannerOutput) {

        if (modeResolver.isCIR()) {
            // CIR ignores event-driven execution
            return;
        }

        log.info(
            "[CriticAgent][EIR] Critiquing plan for task {}",
            plannerOutput.getTaskId()
        );

        String prompt = buildCriticPrompt(plannerOutput);
        String critiqueText = llmClient.generate(prompt);

        CriticFeedback feedback = new CriticFeedback(
                plannerOutput.getTaskId(),
                getAgentId(),
                List.of("Potential logical gaps detected"),
                List.of("Consider validating assumptions"),
                RiskLevel.MEDIUM,
                0.7,
                critiqueText
        );

        eventBus.publish(
                new Event(
                        EventType.CRITIC_FEEDBACK_PRODUCED,
                        getAgentId(),
                        feedback
                )
        );
    }

    /**
     * Event listener (EIR mode only).
     */
    @Override
    public void onEvent(Event event) {

        if (modeResolver.isCIR()) {
            return;
        }

        if (event.getType() == EventType.PLAN_PRODUCED) {
            PlannerOutput plannerOutput =
                    (PlannerOutput) event.getPayload();
            handlePlan(plannerOutput);
        }
    }

    @Override
    public void handleTask(String taskId) {
        // Critic does not directly handle tasks
    }

    // =========================
    // PROMPT BUILDER
    // =========================

    private String buildCriticPrompt(PlannerOutput output) {
        return """
        You are a critical AI reviewer.

        Original Task:
        %s

        Proposed Plan:
        %s

        Your job:
        - Identify logical flaws
        - Identify missing steps
        - Identify risky assumptions
        - Assess feasibility

        Rules:
        - Do NOT rewrite the plan
        - Do NOT execute anything
        - Only critique

        Respond in plain text with:
        - Issues
        - Suggestions
        - Risk Level (LOW, MEDIUM, HIGH)
        - Confidence Score (0.0 to 1.0)
        - Summary
        """.formatted(
                output.getOriginalTask(),
                output.getPlanText()
        );
    }
}
