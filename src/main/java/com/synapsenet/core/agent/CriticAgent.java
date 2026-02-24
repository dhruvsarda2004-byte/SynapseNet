package com.synapsenet.core.agent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.core.critic.CriticFeedback;
import com.synapsenet.core.critic.RiskLevel;
import com.synapsenet.core.executor.ExecutionResult;
import com.synapsenet.core.planner.PlannerOutput;
import com.synapsenet.core.state.SharedState;
import com.synapsenet.llm.LLMClient;

/**
 * CriticAgent — analyses execution results and produces CriticFeedback.
 *
 * PRIMARY entry point: analyze(goal, executionResult, state)
 *   Called by SimpleTaskOrchestrator after every executor run.
 *
 * LEGACY entry point: critique(plannerOutput)
 *   Retained for any direct plan-critique callers.
 *
 * REMOVED: EventBus, SynapseEventListener, ExecutionModeResolver — all CIR remnants.
 */
@Component
public class CriticAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(CriticAgent.class);

    private final LLMClient llmClient;

    public CriticAgent(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String getAgentId() { return "critic-agent-1"; }

    @Override
    public AgentType getAgentType() { return AgentType.CRITIC; }

    // =========================================================================
    // PRIMARY: called by SimpleTaskOrchestrator
    // =========================================================================

    public CriticFeedback analyze(String goal, ExecutionResult executionResult, SharedState state) {

        log.info("[CriticAgent] Analyzing execution for phase={}", state.getCurrentPhase());

        String prompt = buildAnalysisPrompt(goal, executionResult, state);
        log.info("[Critic] Final prompt length: {} chars", prompt.length());

        String critiqueText = llmClient.generateWithRole(
                AgentType.CRITIC,
                prompt,
                llmClient.getTemperatureForRole(AgentType.CRITIC)
        );

        RiskLevel riskLevel = inferRiskLevel(executionResult);
        double    sigma     = inferSatisfactionScore(executionResult);

        log.info("[CriticAgent] risk={}, sigma={}", riskLevel, sigma);

        return new CriticFeedback(
                state.getCurrentPhase() != null ? state.getCurrentPhase().name() : "UNKNOWN",
                getAgentId(),
                List.of("See critique text for issues"),
                List.of("See critique text for suggestions"),
                riskLevel,
                sigma,
                critiqueText
        );
    }

    // =========================================================================
    // LEGACY: plan critique (kept for backward compatibility)
    // =========================================================================

    public CriticFeedback critique(PlannerOutput plannerOutput) {

        log.info("[CriticAgent][legacy] Critiquing plan for task {}", plannerOutput.getTaskId());

        String prompt      = buildCriticPrompt(plannerOutput);
        String critiqueText = llmClient.generate(prompt);

        return new CriticFeedback(
                plannerOutput.getTaskId(),
                getAgentId(),
                List.of("Potential logical gaps detected"),
                List.of("Consider validating assumptions"),
                RiskLevel.MEDIUM,
                0.7,
                critiqueText
        );
    }

    // =========================================================================
    // Prompt builders
    // =========================================================================

    private String buildAnalysisPrompt(String goal, ExecutionResult result, SharedState state) {

        // ── Change 1: Trim error details before injection ─────────────────────
        // getErrorSummary() can return the full pytest output (~15k chars) when
        // run_tests fails. Limit to 20 lines OR 2500 chars, whichever is smaller.
        String errorDetails = result.getErrorSummary();
        if (errorDetails != null && !errorDetails.isEmpty()) {
            // Line-based trim first
            String[] errLines = errorDetails.split("\n", -1);
            if (errLines.length > 20) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 20; i++) sb.append(errLines[i]).append("\n");
                sb.append("\n[... truncated for brevity ...]");
                errorDetails = sb.toString();
            }
            // Character-based trim second (safe — find last newline boundary)
            if (errorDetails.length() > 2500) {
                int cutAt = errorDetails.lastIndexOf('\n', 2500);
                if (cutAt < 0) cutAt = 2500;
                errorDetails = errorDetails.substring(0, cutAt) + "\n\n[... truncated for brevity ...]";
            }
        }

        String prompt = """
                You are a critical AI reviewer analysing a software repair execution.

                Original Goal: %s
                Current Phase: %s

                Execution Summary:
                - Task: %s
                - Tool results: %s
                - Tests ran: %s
                - Tests passed: %s
                - Modified files: %s
                - Has errors: %s

                Error details (if any):
                %s

                Identify: what went wrong, logical flaws, whether execution moved toward the goal.
                Do NOT suggest running tests unless in VALIDATE phase.
                Respond in plain text: Issues, Suggestions, Risk Level (LOW/MEDIUM/HIGH), Confidence (0.0-1.0), Summary.
                """.formatted(
                goal,
                state.getCurrentPhase(),
                result.getTaskDescription(),
                result.getToolExecutionSummary(),
                result.getTestResults() != null && result.getTestResults().wereRun(),
                result.testsPass(),
                result.getModifiedFiles(),
                result.hasErrors(),
                errorDetails
        );

        // ── Budget guard ──────────────────────────────────────────────────────
        if (prompt.length() > 14000) {
            log.warn("[Critic] Prompt exceeded budget ({} chars). Trimming further.", prompt.length());
            prompt = prompt.substring(0, 12000);
        }

        return prompt;
    }

    private String buildCriticPrompt(PlannerOutput output) {
        return """
                You are a critical AI reviewer.
                Original Task: %s
                Proposed Plan: %s
                Identify logical flaws, missing steps, risky assumptions, feasibility issues.
                Do NOT rewrite or execute. Critique only.
                Respond: Issues, Suggestions, Risk Level (LOW/MEDIUM/HIGH), Confidence (0.0-1.0), Summary.
                """.formatted(output.getOriginalTask(), output.getPlanText());
    }

    // =========================================================================
    // Heuristic scoring
    // =========================================================================

    private RiskLevel inferRiskLevel(ExecutionResult result) {
        if (result.hasErrors() && !result.testsPass()) return RiskLevel.HIGH;
        if (result.hasErrors() || !result.testsPass())  return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private double inferSatisfactionScore(ExecutionResult result) {
        if (result.testsPass() && !result.hasErrors()) return 1.0;
        if (result.hasErrors() && !result.testsPass()) return 0.2;
        return 0.5;
    }

    @Override
    public void handleTask(String taskId) {
        // CriticAgent is called directly via analyze() — not task-driven
    }
}