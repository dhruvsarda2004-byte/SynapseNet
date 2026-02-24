package com.synapsenet.core.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PlannerOutput — immutable value object produced by PlannerAgent.
 *
 * CANONICAL JSON SCHEMA (what the LLM must return):
 * {
 *   "repair_steps": [ "Step 1: ...", "Step 2: ..." ],
 *   "reasoning":    "Why this plan"
 * }
 *
 * The step list is stored as investigationSteps internally. SharedState accesses
 * it via getInvestigationSteps(). The JSON key is always "repair_steps".
 * fromJson() also accepts the legacy key "investigation_steps" as a fallback.
 *
 * STATIC FACTORIES (the only construction paths):
 *   fromJson(goal, jsonText)  — parse from raw LLM output; falls back to fallback() on error
 *   repairFallback(goal)      — guaranteed-safe REPAIR_PATCH plan (no test steps)
 *   fallback(goal)            — generic REPRODUCE-phase fallback (contains test steps)
 *
 * INVARIANT: repairFallback() must NEVER contain any step that mentions "test" or "reproduce".
 * This is checked by the orchestrator's plan validator before use in REPAIR_PATCH phase.
 */
public class PlannerOutput {

    private static final Logger       log    = LoggerFactory.getLogger(PlannerOutput.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String       taskId;
    private final String       plannerAgentId;
    private final String       originalTask;
    private final String       planText;           // raw LLM response (for logging/debug)
    private final String       reasoning;
    private final List<String> investigationSteps; // ordered steps; never null, may be empty
    private final Instant      createdAt;

    // =========================================================================
    // Constructor (private — use static factories)
    // =========================================================================

    private PlannerOutput(
            String       taskId,
            String       plannerAgentId,
            String       originalTask,
            String       planText,
            String       reasoning,
            List<String> investigationSteps
    ) {
        this.taskId             = taskId;
        this.plannerAgentId     = plannerAgentId;
        this.originalTask       = originalTask;
        this.planText           = planText != null ? planText : "";
        this.reasoning          = reasoning != null ? reasoning : "";
        this.investigationSteps = investigationSteps != null
                ? List.copyOf(investigationSteps)
                : List.of();
        this.createdAt          = Instant.now();
    }

    // =========================================================================
    // Static factories
    // =========================================================================

    /**
     * Parse from raw LLM JSON text.
     *
     * Key priority: "repair_steps" → "investigation_steps" → empty (fallback).
     * Strips markdown code fences and leading prose before parsing.
     * Never returns null; returns fallback(goal) on any parse error.
     */
    public static PlannerOutput fromJson(String goal, String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            log.warn("[PlannerOutput] Null/blank LLM response — using fallback");
            return fallback(goal);
        }

        try {
            String cleaned = jsonText.trim();

            // Strip markdown fences
            if (cleaned.startsWith("```")) {
                int start = cleaned.indexOf('\n') + 1;
                int end   = cleaned.lastIndexOf("```");
                if (end > start) cleaned = cleaned.substring(start, end).trim();
            }

            // Skip leading prose to first '{'
            int jsonStart = cleaned.indexOf('{');
            if (jsonStart > 0) cleaned = cleaned.substring(jsonStart);

            JsonNode root = MAPPER.readTree(cleaned);

            // Read steps — canonical key first, legacy key fallback
            List<String> steps = new ArrayList<>();
            JsonNode stepsNode = root.has("repair_steps")
                    ? root.get("repair_steps")
                    : root.get("investigation_steps");

            if (stepsNode != null && stepsNode.isArray()) {
                for (JsonNode step : stepsNode) {
                    String text = step.asText().trim();
                    if (!text.isEmpty()) steps.add(text);
                }
            }

            if (steps.isEmpty()) {
                log.warn("[PlannerOutput] JSON parsed but no steps found — using fallback");
                return fallback(goal);
            }

            String reasoning = root.has("reasoning") ? root.get("reasoning").asText() : "";
            log.info("[PlannerOutput] Parsed {} steps", steps.size());

            return new PlannerOutput(goal, "planner-agent-1", goal, jsonText, reasoning, steps);

        } catch (Exception e) {
            log.error("[PlannerOutput] JSON parse failed: {} — using fallback", e.getMessage());
            return fallback(goal);
        }
    }

    /**
     * Safe REPAIR_PATCH fallback.
     *
     * INVARIANT: must contain NO step mentioning "test" or "reproduce".
     * Called by the orchestrator when REPAIR plan validation fails after
     * MAX_PLAN_VALIDATION_RETRIES. Constructed directly (not via fromJson)
     * to guarantee the invariant regardless of LLM output.
     */
    public static PlannerOutput repairFallback(String goal) {
        return new PlannerOutput(
                goal,
                "planner-agent-1",
                goal,
                "[repairFallback]",
                "Safe fallback: atomic read-and-patch with no test steps",
                List.of(
                        "Read the failing source file identified in the root cause analysis " +
                        "and immediately apply the fix using replace_in_file. " +
                        "You MUST call replace_in_file before finishing."
                )
        );
    }

    /**
     * Generic REPRODUCE-phase fallback. Contains test-running steps.
     * Must NOT be used in REPAIR_PATCH phase.
     */
    public static PlannerOutput fallback(String goal) {
        return new PlannerOutput(
                goal,
                "planner-agent-1",
                goal,
                "[fallback]",
                "Generic fallback plan",
                List.of(
                        "Step 1: Use file_tree to discover project structure",
                        "Step 2: Run tests to reproduce failure"
                )
        );
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /** Task identifier — currently equal to the goal string. */
    public String getTaskId() { return taskId; }

    public String getPlannerAgentId() { return plannerAgentId; }

    /** The original goal/task string passed to the planner. */
    public String getOriginalTask() { return originalTask; }

    /** Raw LLM response text. Used by CriticAgent.critique() for prompt injection. */
    public String getPlanText() { return planText; }

    public String getReasoning() { return reasoning; }

    /**
     * Ordered list of repair/investigation steps.
     * Read by SharedState.getCurrentTask() and the orchestrator plan validator.
     * Never null; may be empty (caller should check getStepCount() > 0).
     */
    public List<String> getInvestigationSteps() { return investigationSteps; }

    /**
     * Step count. Zero means the plan is empty; caller should substitute a fallback.
     */
    public int getStepCount() { return investigationSteps.size(); }

    public Instant getCreatedAt() { return createdAt; }
}