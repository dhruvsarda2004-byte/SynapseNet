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
import com.synapsenet.core.mediator.MediationDecision;
import com.synapsenet.core.mediator.MediationResult;
import com.synapsenet.core.planner.PlannerOutput;
import com.synapsenet.llm.LLMClient;

@Component
public class MediatorAgent implements Agent, SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(MediatorAgent.class);

    // Paper-safe constants
    private static final double ALPHA = 0.7; // critic confidence
    private static final double BETA  = 0.3; // stability
    private static final double APPROVAL_THRESHOLD = 0.65;


    private final EventBus eventBus;
    private final LLMClient llmClient; // used ONLY in EIR
    private final ExecutionModeResolver modeResolver;

    // ===== EIR STATE =====
    private final Map<String, PlannerOutput> plans = new HashMap<>();
    private final Map<String, CriticFeedback> critiques = new HashMap<>();
    private final Map<String, Boolean> blockedTasks = new HashMap<>();

    public MediatorAgent(
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
        return "mediator-agent-1";
    }

    @Override
    public AgentType getAgentType() {
        return AgentType.MEDIATOR;
    }

    @Override
    public void handleTask(String taskId) {
        // Mediator never handles tasks directly
    }

    /* =========================================================
       ======================= CIR MODE ========================
       ========================================================= */

    public MediationResult mediate(
            PlannerOutput currentPlan,
            CriticFeedback critique,
            PlannerOutput previousPlan
    ) {

        log.info(
            "[MediatorAgent][CIR] Mediating task {}",
            currentPlan.getTaskId()
        );

        double sigma = critique.getConfidenceScore();
        double delta = computeStability(currentPlan, previousPlan);

        double gamma = (ALPHA * sigma) + (BETA * delta);

        MediationDecision decision =
                gamma >= APPROVAL_THRESHOLD
                        ? MediationDecision.APPROVE_EXECUTION
                        : MediationDecision.REQUEST_REPLAN;

        String reasoning = buildDeterministicReasoning(
                sigma, delta, gamma, decision
        );

        log.info(
            "[MediatorAgent][CIR] σ={} Δ={} γ={} decision={}",
            sigma,
            delta,
            gamma,
            decision
        );

        return new MediationResult(
                currentPlan.getTaskId(),
                getAgentId(),
                decision,
                reasoning,
                gamma
        );
    }

    /* =========================================================
       ======================= EIR MODE ========================
       ========================================================= */

    @Override
    public void onEvent(Event event) {

        if (modeResolver.isCIR()) {
            return;
        }

        if (event.getType() == EventType.PLAN_PRODUCED) {
            PlannerOutput plan = (PlannerOutput) event.getPayload();
            plans.put(plan.getTaskId(), plan);
        }

        if (event.getType() == EventType.CRITIC_FEEDBACK_PRODUCED) {
            CriticFeedback feedback =
                    (CriticFeedback) event.getPayload();
            critiques.put(feedback.getTaskId(), feedback);
        }

        if (event.getType() == EventType.TASK_BLOCKED) {
            CriticFeedback feedback =
                    (CriticFeedback) event.getPayload();
            blockedTasks.put(feedback.getTaskId(), true);
            tryMediate(feedback.getTaskId());
        }
    }

    private void tryMediate(String taskId) {

        if (!plans.containsKey(taskId)
                || !critiques.containsKey(taskId)) {
            return;
        }

        PlannerOutput plan = plans.get(taskId);
        CriticFeedback critique = critiques.get(taskId);

        log.info("[MediatorAgent][EIR] Mediating task {}", taskId);

        // ✅ EIR can afford LLM reasoning
        String reasoning = llmClient.generate(
                buildMediatorPrompt(plan, critique)
        );

        MediationDecision decision =
                critique.getRiskLevel() == RiskLevel.HIGH
                        ? MediationDecision.REQUEST_REPLAN
                        : MediationDecision.APPROVE_EXECUTION;

        MediationResult result = new MediationResult(
                taskId,
                getAgentId(),
                decision,
                reasoning,
                0.75
        );

        publishDecision(result);

        plans.remove(taskId);
        critiques.remove(taskId);
        blockedTasks.remove(taskId);
    }

    private void publishDecision(MediationResult result) {

        EventType type = switch (result.getDecision()) {
            case APPROVE_EXECUTION -> EventType.MEDIATION_APPROVED;
            case REJECT_EXECUTION -> EventType.MEDIATION_REJECTED;
            case REQUEST_REPLAN -> EventType.MEDIATION_REPLAN_REQUESTED;
        };

        eventBus.publish(
                new Event(
                        type,
                        getAgentId(),
                        result
                )
        );
    }

    /* =========================================================
       ======================= HELPERS =========================
       ========================================================= */

    private double computeStability(
            PlannerOutput current,
            PlannerOutput previous
    ) {
        if (previous == null) {
            return 0.0;
        }

        return current.getPlanText()
                .equals(previous.getPlanText())
                ? 1.0
                : 0.5;
    }

    private String buildDeterministicReasoning(
            double sigma,
            double delta,
            double gamma,
            MediationDecision decision
    ) {
        return """
        Mediation Decision Summary:
        - Critic confidence (σ): %.2f
        - Plan stability (Δ): %.2f
        - Combined score (γ): %.2f

        Decision: %s

        Rationale:
        The decision was derived deterministically using weighted confidence
        and stability, without probabilistic language model influence.
        """.formatted(sigma, delta, gamma, decision);
    }

    private String buildMediatorPrompt(
            PlannerOutput plan,
            CriticFeedback critique
    ) {
        return """
        You are a neutral AI mediator.

        Original Task:
        %s

        Planner Proposal:
        %s

        Critic Feedback:
        %s

        Decide whether execution should proceed or replanning is required.
        """.formatted(
                plan.getOriginalTask(),
                plan.getPlanText(),
                critique.getSummary()
        );
    }
}
