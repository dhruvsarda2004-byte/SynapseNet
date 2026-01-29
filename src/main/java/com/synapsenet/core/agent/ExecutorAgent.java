package com.synapsenet.core.agent;

import java.util.HashMap;
import java.util.Map;

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
public class ExecutorAgent implements Agent, SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(ExecutorAgent.class);

    private final EventBus eventBus;
    private final ExecutionModeResolver modeResolver;
    private final LLMClient llmClient;

    // ===== EIR INTERNAL STATE =====
    private final Map<String, PlannerOutput> plans = new HashMap<>();
    private final Map<String, CriticFeedback> critiques = new HashMap<>();

    public ExecutorAgent(
            EventBus eventBus,
            ExecutionModeResolver modeResolver,
            LLMClient llmClient
    ) {
        this.eventBus = eventBus;
        this.modeResolver = modeResolver;
        this.llmClient = llmClient;
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
        // Executor never handles tasks directly
    }

    /**
     * =========================
     * CIR ENTRY POINT
     * =========================
     *
     * Produces the FINAL USER-FACING ANSWER
     * with strict presentation formatting.
     */
    public void execute(PlannerOutput finalPlan) {

        log.info(
            """
            ============================================================
            [EXECUTOR][CIR] EXECUTING FINAL PLAN
            Task : {}
            ============================================================
            """,
            finalPlan.getTaskId()
        );

        String executionPrompt = buildStructuredExecutorPrompt(finalPlan);

        String finalAnswer = llmClient.generate(executionPrompt);

        log.info(
            """
            ===================== FINAL ANSWER =====================
            {}
            ========================================================
            """,
            finalAnswer
        );
    }

    /**
     * Enforces strict separation between:
     * - execution metadata
     * - systematic explanation
     * - final answer
     *
     * This is a PRESENTATION-LAYER contract.
     */
    private String buildStructuredExecutorPrompt(PlannerOutput finalPlan) {

        return """
    You are the Executor Agent.

    Your role is to produce the FINAL USER-FACING RESPONSE.
    All reasoning, planning, critique, and mediation are COMPLETE and MUST NOT be discussed.

    STRICT RULES (NON-NEGOTIABLE):
    - Do NOT evaluate, describe, summarize, or praise any plan.
    - Do NOT mention steps, structure, refinement, or strategy.
    - Do NOT explain how the answer was produced.
    - Do NOT include tables, headings about plans, or meta text.

    You MUST follow the exact output format below.

    ======================
    OUTPUT FORMAT (MANDATORY)
    ======================

    EXECUTION SUMMARY
    - Task ID: %s
    - CIR Iterations: %d
    - Final Confidence (γ): %.2f
    - Decision: %s

    (blank line)

    SYSTEMATIC EXPLANATION
    Provide 3–5 numbered, factual points explaining the concept.
    Each point must be concise and technical.
    NO meta commentary.

    (blank line)

    FINAL ANSWER
    Write ONE clean paragraph that directly answers the user question.

    ======================

    ONLY output text in this format.
    DO NOT output anything else.

    User Question:
    %s
    """.formatted(
            finalPlan.getTaskId(),
            /* iterations */ 6,          // keep as-is or wire later
            /* gamma */ 0.64,             // keep as-is or wire later
            "REQUEST_REPLAN",
            finalPlan.getOriginalTask()
        );
    }


    // =========================
    // EIR ENTRY POINT
    // =========================
    @Override
    public void onEvent(Event event) {

        if (modeResolver.isCIR()) {
            return;
        }

        if (event.getType() == EventType.PLAN_PRODUCED) {
            PlannerOutput plan = (PlannerOutput) event.getPayload();
            plans.put(plan.getTaskId(), plan);
            tryDecide(plan.getTaskId());
        }

        if (event.getType() == EventType.CRITIC_FEEDBACK_PRODUCED) {
            CriticFeedback feedback =
                    (CriticFeedback) event.getPayload();
            critiques.put(feedback.getTaskId(), feedback);
            tryDecide(feedback.getTaskId());
        }
    }

    private void tryDecide(String taskId) {

        if (!plans.containsKey(taskId)
                || !critiques.containsKey(taskId)) {
            return;
        }

        CriticFeedback feedback = critiques.get(taskId);

        if (feedback.getRiskLevel() == RiskLevel.HIGH) {
            block(taskId, feedback);
        } else {
            execute(taskId);
        }

        plans.remove(taskId);
        critiques.remove(taskId);
    }

    private void execute(String taskId) {

        log.info("[ExecutorAgent][EIR] EXECUTING task {}", taskId);

        eventBus.publish(
                new Event(
                        EventType.TASK_EXECUTED,
                        getAgentId(),
                        taskId
                )
        );
    }

    private void block(String taskId, CriticFeedback feedback) {

        log.warn(
            "[ExecutorAgent][EIR] BLOCKING task {} due to HIGH risk. Summary: {}",
            taskId,
            feedback.getSummary()
        );

        eventBus.publish(
                new Event(
                        EventType.TASK_BLOCKED,
                        getAgentId(),
                        feedback
                )
        );
    }
}
