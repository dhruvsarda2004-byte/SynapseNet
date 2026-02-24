package com.synapsenet.llm;

import com.synapsenet.core.agent.AgentType;

/**
 * LLMClient — single interface for all LLM interactions in SynapseNet.
 *
 * ONE method contract: generateWithRole(AgentType, String, double).
 * generate(String) is a convenience default that calls generateWithRole
 * with AgentType.EXECUTOR and temperature 0.3 — it exists so legacy call
 * sites compile without change. New code must always call generateWithRole.
 *
 * getTemperatureForRole(AgentType) is a default method so the canonical
 * temperatures live here, not scattered across agents.
 *
 * REMOVED: no separate generate(String) abstract method. A single abstract
 * method keeps implementations unambiguous.
 */
public interface LLMClient {

    /**
     * Primary generation method. All agents must call this.
     *
     * @param role        Agent role — drives system prompt selection in the implementation.
     * @param userPrompt  Task-specific prompt body.
     * @param temperature Sampling temperature (0.0 = deterministic, 1.0 = creative).
     * @return Raw LLM response text. Never null; empty string on empty model output.
     */
    String generateWithRole(AgentType role, String userPrompt, double temperature);

    /**
     * Convenience wrapper for call sites that have a plain prompt with no role context.
     * Delegates to generateWithRole with a neutral role and default temperature.
     * DO NOT override — implementations only need to implement generateWithRole.
     */
    default String generate(String prompt) {
        return generateWithRole(AgentType.EXECUTOR, prompt, 0.3);
    }

    /**
     * Canonical per-role sampling temperatures.
     * Agents call this instead of hardcoding values.
     *
     * PLANNER   0.2 — structured JSON output; low creativity
     * EXECUTOR  0.1 — deterministic tool calls
     * CRITIC    0.4 — analytical reasoning; moderate variance
     * MEDIATOR  0.0 — rule-based; fully deterministic
     * default   0.3
     */
    default double getTemperatureForRole(AgentType role) {
        return switch (role) {
            case PLANNER  -> 0.2;
            case EXECUTOR -> 0.1;
            case CRITIC   -> 0.4;
            case MEDIATOR -> 0.0;
            default       -> 0.3;
        };
    }
}