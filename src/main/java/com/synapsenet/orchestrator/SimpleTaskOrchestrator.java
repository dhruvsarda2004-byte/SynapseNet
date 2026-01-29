package com.synapsenet.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.config.ExecutionModeResolver;
import com.synapsenet.core.agent.CriticAgent;
import com.synapsenet.core.agent.ExecutorAgent;
import com.synapsenet.core.agent.MediatorAgent;
import com.synapsenet.core.agent.PlannerAgent;
import com.synapsenet.core.mediator.MediationResult;
import com.synapsenet.core.planner.PlannerOutput;
import com.synapsenet.orchestrator.dto.CIRResult;

@Component
public class SimpleTaskOrchestrator {

    private static final Logger log =
            LoggerFactory.getLogger(SimpleTaskOrchestrator.class);

    private static final int MAX_ITERATIONS = 5;
    private static final double CONFIDENCE_THRESHOLD = 0.65;

    private static final String ITERATION_BAR =
            "\n============================================================";
    private static final String PHASE_BAR =
            "------------------------------------------------------------";

    private final ExecutionModeResolver modeResolver;
    private final PlannerAgent planner;
    private final CriticAgent critic;
    private final MediatorAgent mediator;
    private final ExecutorAgent executor;

    public SimpleTaskOrchestrator(
            ExecutionModeResolver modeResolver,
            PlannerAgent planner,
            CriticAgent critic,
            MediatorAgent mediator,
            ExecutorAgent executor
    ) {
        this.modeResolver = modeResolver;
        this.planner = planner;
        this.critic = critic;
        this.mediator = mediator;
        this.executor = executor;
    }

    public CIRResult runTask(String taskId) {

        if (!modeResolver.isCIR()) {
            throw new IllegalStateException("CIR mode is disabled");
        }

        log.info(
            "{}\n[CIR] STARTING TASK\nTask : {}\n{}",
            ITERATION_BAR,
            taskId,
            ITERATION_BAR
        );

        PlannerOutput previousPlan = null;
        PlannerOutput finalPlan = null;
        MediationResult finalDecision = null;

        int iteration;

        for (iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {

            log.info(
                "{}\n[CIR] ITERATION {}\n{}",
                ITERATION_BAR,
                iteration,
                ITERATION_BAR
            );

            // ================= PLANNER =================
            PlannerOutput currentPlan =
                    planner.generatePlan(taskId, previousPlan);

            log.info(
                """
                [PLANNER OUTPUT]
                {}
                {}
                """,
                currentPlan.getPlanText(),
                PHASE_BAR
            );

            // ================= CRITIC =================
            var critique = critic.critique(currentPlan);

            log.info(
                """
                [CRITIC FEEDBACK]
                σ (confidence) : {}
                Risk Level    : {}
                Summary       : {}
                {}
                """,
                critique.getConfidenceScore(),
                critique.getRiskLevel(),
                critique.getSummary(),
                PHASE_BAR
            );

            // ================= MEDIATOR =================
            MediationResult decision =
                    mediator.mediate(
                            currentPlan,
                            critique,
                            previousPlan
                    );

            log.info(
                """
                [MEDIATOR DECISION]
                γ (final score) : {}
                Decision        : {}
                {}
                """,
                decision.getConfidenceScore(),
                decision.getDecision(),
                ITERATION_BAR
            );

            finalPlan = currentPlan;
            finalDecision = decision;

            if (decision.isApproved()
                    && decision.getConfidenceScore() >= CONFIDENCE_THRESHOLD) {

                log.info(
                    """
                    ============================================================
                    [CIR] TERMINATION CONDITION MET
                    → EXECUTION APPROVED
                    ============================================================
                    """
                );

                executor.execute(currentPlan);
                break;
            }

            previousPlan = currentPlan;
        }

        return new CIRResult(
                taskId,
                iteration,
                finalDecision.getConfidenceScore(),
                finalDecision.getDecision().name(),
                finalPlan.getPlanText()
        );
    }
}
