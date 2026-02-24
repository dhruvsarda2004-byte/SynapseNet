package com.synapsenet.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsenet.core.agent.AgentType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * OllamaLLMClient — production LLMClient backed by a local Ollama server.
 *
 * Implements only generateWithRole(). The generate() and getTemperatureForRole()
 * methods are provided by the LLMClient interface defaults and must NOT be
 * overridden here — all temperature and delegation logic belongs in the interface.
 *
 * System prompt injection is the sole responsibility of this class.
 * Role → system prompt mapping is the single source of truth here.
 * Agents must NOT embed system prompts in their own prompt builders.
 */
@Component
public class OllamaLLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLLMClient.class);

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.model:llama3:8b}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper  = new ObjectMapper();

    // =========================================================================
    // LLMClient contract
    // =========================================================================

    @Override
    public String generateWithRole(AgentType role, String userPrompt, double temperature) {
        String systemPrompt = getSystemPromptForRole(role);
        String fullPrompt   = systemPrompt + "\n\n" + userPrompt;

        log.debug("[Ollama] role={} temperature={} promptLen={}", role, temperature, fullPrompt.length());

        return callOllama(fullPrompt, temperature);
    }

    // =========================================================================
    // System prompts — single source of truth for agent personas
    // =========================================================================

    private String getSystemPromptForRole(AgentType role) {
        return switch (role) {
            case PLANNER -> """
                    You are a precise software debugger and repair planner.
                    Produce a structured JSON repair plan with a "repair_steps" array.
                    Output ONLY valid JSON. No prose outside the JSON object.
                    Never include test-running steps in REPAIR_ANALYZE or REPAIR_PATCH phases.
                    """;

            case EXECUTOR -> """
                    You are a precise software repair executor.
                    Execute repair tasks by emitting structured JSON tool calls.
                    Output ONLY valid JSON. No prose outside JSON.
                    When patching, you MUST call replace_in_file with exact search/replace blocks.
                    """;

            case CRITIC -> """
                    You are a critical AI code reviewer.
                    Identify logical flaws, missing steps, risky assumptions, feasibility issues.
                    Do NOT rewrite or execute anything. Critique only.
                    Respond in plain text: Issues, Suggestions, Risk Level, Confidence Score, Summary.
                    """;

            case MEDIATOR -> """
                    You are a rule-based state machine decision engine.
                    Return a structured decision: SUCCESS, FAIL, ADVANCE, RETRY, or REPLAN.
                    Output ONLY valid JSON with "decision" and "reasoning" fields.
                    """;

            default -> "You are a helpful AI assistant. Be precise and concise.";
        };
    }

    // =========================================================================
    // HTTP client
    // =========================================================================

    private String callOllama(String prompt, double temperature) {
        try {
            String url = baseUrl + "/api/generate";

            Map<String, Object> body = new HashMap<>();
            body.put("model",       model);
            body.put("prompt",      prompt);
            body.put("temperature", temperature);
            body.put("stream",      false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);

            JsonNode root = objectMapper.readTree(response.getBody());

            String result = root.has("response") ? root.get("response").asText() : "";
            log.debug("[Ollama] responseLen={}", result.length());
            return result;

        } catch (Exception e) {
            log.error("[Ollama] Call failed: {}", e.getMessage());
            throw new RuntimeException("Ollama LLM call failed: " + e.getMessage(), e);
        }
    }
}