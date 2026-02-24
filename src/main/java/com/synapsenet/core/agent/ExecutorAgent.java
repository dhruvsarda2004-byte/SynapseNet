package com.synapsenet.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsenet.core.executor.ExecutionResult;
import com.synapsenet.core.executor.PytestOutputAnalyzer;
import com.synapsenet.core.executor.TestFailureType;
import com.synapsenet.core.executor.TestResults;
import com.synapsenet.core.executor.ToolExecutor;
import com.synapsenet.core.executor.ToolResult;
import com.synapsenet.core.state.RepairAttempt;
import com.synapsenet.core.state.RepairPhase;
import com.synapsenet.core.state.RootCauseAnalysis;
import com.synapsenet.core.state.SharedState;
import com.synapsenet.llm.LLMClient;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExecutorAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ExecutorAgent.class);

    // ✅ CONTEXT WINDOW: Ollama's default context is 4096 tokens.
    // rewrite.py is 1084 lines (~41k chars) — injecting it in full causes silent
    // truncation, so the LLM never sees the failure site and hallucinates search blocks.
    // We inject only CONTEXT_WINDOW_LINES before and after the known failure line instead.
    private static final int CONTEXT_WINDOW_LINES    = 80;    // lines above + below failure line
    private static final int MAX_FILE_LINES_FALLBACK = 120;   // used when line number is unknown

    private final LLMClient llmClient;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper jsonMapper;
    private final PytestOutputAnalyzer pytestAnalyzer;

    public ExecutorAgent(LLMClient llmClient, ToolExecutor toolExecutor) {
        this.llmClient    = llmClient;
        this.toolExecutor = toolExecutor;
        this.jsonMapper   = new ObjectMapper();
        this.pytestAnalyzer = new PytestOutputAnalyzer();
    }

    @Override public String getAgentId()       { return "executor-agent-1"; }
    @Override public AgentType getAgentType()  { return AgentType.EXECUTOR; }

    public ExecutionResult execute(String currentTask, SharedState state) {

        log.info("[Executor] Executing task: {}", currentTask);
        log.info("[Executor] Current phase: {}", state.getCurrentPhase());

        // ================================================================
        // REPAIR_ANALYZE: separate execution path — no tools, raw JSON response
        // ================================================================
        if (state.getCurrentPhase() == RepairPhase.REPAIR_ANALYZE) {
            return executeRepairAnalyze(currentTask, state);
        }

        // ================================================================
        // Normal execution path (REPRODUCE, REPAIR_PATCH, VALIDATE)
        // ================================================================
        String prompt = buildExecutionPrompt(currentTask, state);
        log.info("[LLM] Executor prompt length: {}", prompt.length());

        String llmResponse = llmClient.generateWithRole(
                AgentType.EXECUTOR,
                prompt,
                llmClient.getTemperatureForRole(AgentType.EXECUTOR)
        );

        log.info("[Executor] LLM response received ({} chars)", llmResponse.length());

        ParseResult parsed = parseToolCallsFromResponse(llmResponse);

        if (parsed.calls.isEmpty() && parsed.failedWithJson) {
            log.warn("[Executor] Malformed JSON detected — re-prompting");
            llmResponse = retryWithJsonEnforcement(prompt, llmResponse);
            parsed = parseToolCallsFromResponse(llmResponse);
        }

        List<ToolCall> toolCalls = parsed.calls;

        List<ToolCall> gatedCalls    = enforceDiscoveryGate(toolCalls, state);
        List<ToolCall> evidenceCalls = enforceRepairEvidenceGate(gatedCalls, state);
        List<ToolCall> filteredCalls = filterToolsByPhase(evidenceCalls, state);

        log.info("[Executor] Executing {} tools (filtered from {})",
                filteredCalls.size(), toolCalls.size());

        return executeTools(filteredCalls, currentTask, state);
    }

    // ====================================================================
    // REPAIR_ANALYZE EXECUTION PATH
    // ====================================================================

    /**
     * Execute REPAIR_ANALYZE phase: send analysis prompt, parse raw JSON,
     * validate into RootCauseAnalysis, store in SharedState.
     *
     * Tool calls are structurally absent — the prompt instructs raw JSON output,
     * getAllowedTools returns empty for REPAIR_ANALYZE, and this method never
     * calls executeTools(). The LLM response is routed directly to parseAnalysis().
     *
     * Returns an ExecutionResult with:
     *   - empty toolResults (no tools executed)
     *   - empty modifiedFiles (no patch)
     *   - TestResults.notRun()
     *   - hasErrors() == false on success, true if parse fails
     */
    private ExecutionResult executeRepairAnalyze(String currentTask, SharedState state) {

        String prompt = buildRepairAnalyzePrompt(currentTask, state);
        
        // ===== DIAGNOSTIC LOGGING (PART 4) =====
        // STATE SNAPSHOT before REPAIR_ANALYZE validation
        log.info("[STATE SNAPSHOT] artifact={}, line={}, cachedFiles={}",
                 state.getFailingArtifact(),
                 state.getFailingArtifactLine(),
                 state.getRecentFileReads().keySet());
        
        log.info("[Executor] REPAIR_ANALYZE prompt length: {}", prompt.length());

        String llmResponse = llmClient.generateWithRole(
                AgentType.EXECUTOR,
                prompt,
                llmClient.getTemperatureForRole(AgentType.EXECUTOR)
        );

        log.info("[Executor] REPAIR_ANALYZE response received ({} chars)", llmResponse.length());

        RootCauseAnalysis analysis = parseAnalysis(llmResponse, state);
        state.setRootCauseAnalysis(analysis);

        // ===== DIAGNOSTIC LOGGING (PART 4) =====
        // ANALYZE RESULT: structured metadata from LLM response
        log.info("[ANALYZE RESULT] artifactPath={}, artifactLine={}",
                 analysis.getArtifactPath(),
                 analysis.getArtifactLine());

        if (analysis.isValid()) {
            log.info("[Executor] REPAIR_ANALYZE: Valid analysis stored — {}",
                    analysis.getRootCauseSummary());
        } else {
            log.warn("[Executor] REPAIR_ANALYZE: Analysis invalid — {}",
                    analysis.getInvalidReason());
        }

        // Always return a clean (no-error) ExecutionResult.
        // The Mediator's handleRepairAnalyzePhase() reads state.hasValidRootCauseAnalysis()
        // directly — it does not rely on hasErrors() for this phase.
        // Empty modifiedFiles and notRun() ensure no false-positive patch or test signals.
        return new ExecutionResult(
                currentTask,
                List.of(),
                TestResults.notRun(),
                List.of()
        );
    }

    /**
     * Build the REPAIR_ANALYZE prompt.
     *
     * No tool definitions exposed. Instructs the LLM to return ONLY raw JSON.
     * Injects file window, failure context, and (on replan cycles) prior analysis.
     */
    /**
     * Build the REPAIR_ANALYZE prompt.
     *
     * Change 1: Raw pytest failure output injected (exception type, message, stack frames).
     *           LLM diagnoses from actual failure cause, not file position.
     * Change 2: Prior failed diagnoses from repairHistory injected directly into ANALYZE
     *           (not only the Planner). LLM instructed to materially differ.
     * Change 3: proposedSearchBlock field in JSON schema — LLM must copy from shown code.
     *           Validated by RootCauseAnalysis against cached file content before advancing.
     * Change 4: No line-number anchor in prompt framing. LLM finds the faulty line
     *           from the exception output, not from a pre-supplied hint.
     */
    private String buildRepairAnalyzePrompt(String currentTask, SharedState state) {

        StringBuilder context = new StringBuilder();

        String artifact = state.getFailingArtifact();
        int    failLine = state.getFailingArtifactLine();

        // ── Change 1: Raw pytest failure output ───────────────────────────────
        // Pass the actual exception type, message, and top stack frames.
        // Do NOT summarize — the LLM needs the real signal to diagnose correctly.
        TestResults lastResults = state.getLastTestResults();
        if (lastResults != null) {
            String rawOutput = lastResults.getRawOutput();
            if (rawOutput != null && !rawOutput.isBlank()) {
                String[] outputLines = rawOutput.split("\n", -1);
                int      limit       = Math.min(outputLines.length, 40);
                StringBuilder excerpt = new StringBuilder();
                for (int i = 0; i < limit; i++) {
                    excerpt.append(outputLines[i]).append("\n");
                }
                if (outputLines.length > limit) {
                    excerpt.append("... (").append(outputLines.length - limit)
                           .append(" more lines omitted)\n");
                }
                context.append("=== FAILURE OUTPUT (raw pytest) ===\n");
                context.append(excerpt);
                context.append("=== END FAILURE OUTPUT ===\n\n");
                log.info("[Executor] REPAIR_ANALYZE: injected {} lines of raw failure output", limit);
            } else {
                // Fallback to structured summary if raw output unavailable
                context.append("=== FAILURE SUMMARY ===\n");
                context.append(lastResults.getDetailedFailureSummary());
                context.append("\n=== END FAILURE SUMMARY ===\n\n");
            }
        }

        // Artifact stated as fact — not as a diagnostic anchor (Change 4)
        if (artifact != null) {
            context.append("Failing file: ").append(artifact).append("\n\n");
        }

        // ── File window ───────────────────────────────────────────────────────
        // ✅ FIX: Only inject the FAILING ARTIFACT, not all cached files.
        // Previously injected ALL cached files, causing LLM to analyze wrong file.
        if (artifact != null && state.hasReadFile(artifact)) {
            // Failing artifact is cached — inject it specifically
            Map<String, String> files = state.getRecentFileReads();
            String artifactContent = files.get(artifact);
            
            // Handle path normalization variations
            if (artifactContent == null) {
                for (String key : files.keySet()) {
                    if (key.endsWith(artifact) || artifact.endsWith(key)) {
                        artifactContent = files.get(key);
                        artifact = key;  // Use cached key for logging
                        break;
                    }
                }
            }
            
            if (artifactContent != null) {
                context.append("=== FILE CONTENT (failing file) ===\n");
                String excerpt = extractFileWindow(artifactContent, failLine, artifact);
                context.append("File: ").append(artifact).append("\n");
                context.append("```python\n");
                context.append(excerpt);
                context.append("\n```\n\n");
                context.append("=== END FILE CONTENT ===\n\n");
                log.info("[Executor] REPAIR_ANALYZE: Injected file window for {}: lines around {}", 
                         artifact, failLine);
            }
        } else if (artifact != null) {
            // Failing artifact not yet cached — LLM must rely on exception output
            // (Evidence gate in REPAIR_PATCH will read it before patching)
            context.append("Note: File content for ").append(artifact)
                    .append(" not yet cached.\n");
            context.append("Use the exception output above to identify the faulty line.\n\n");
            log.warn("[Executor] REPAIR_ANALYZE: Failing artifact not cached — {}",
                     artifact);
        }

        // ── Change 2: Prior failed diagnoses in ANALYZE prompt ────────────────
        // Injected here (not only in Planner) so the LLM sees what failed
        // and produces a materially different diagnosis.
        List<RepairAttempt> history = state.getRepairHistory();
        RootCauseAnalysis   prior   = state.getLastRootCauseAnalysis();

        if (prior != null || !history.isEmpty()) {
            context.append("=== PREVIOUS FAILED DIAGNOSES — DO NOT REPEAT THESE ===\n");

            if (prior != null) {
                context.append("Most recent diagnosis (led to failed patch):\n");
                context.append("  Root cause : ").append(prior.getRootCauseSummary()).append("\n");
                context.append("  Fix tried  : ").append(prior.getMinimalFixStrategy()).append("\n");
                context.append("  ⚠ This was WRONG. Re-examine the exception output above.\n\n");
            }

            if (!history.isEmpty()) {
                context.append("Prior attempts:\n");
                int idx = 1;
                for (RepairAttempt attempt : history) {
                    context.append("Attempt ").append(idx++).append(":\n");
                    if (attempt.getRootCauseSummary() != null) {
                        context.append("  Diagnosis  : ").append(attempt.getRootCauseSummary()).append("\n");
                    }
                    if (attempt.getMinimalFixStrategy() != null) {
                        context.append("  Fix planned: ").append(attempt.getMinimalFixStrategy()).append("\n");
                    }
                    context.append("  Outcome    : ").append(attempt.getOutcome()).append("\n");
                    context.append("  ──\n");
                }
            }

            context.append("Your new analysis MUST differ materially from all diagnoses above.\n");
            context.append("=== END PREVIOUS DIAGNOSES ===\n\n");
        }

        return """
                You are performing ROOT CAUSE ANALYSIS for a failing Python test suite.

                Study the FAILURE OUTPUT — it shows the exact exception type, message, and stack frames.
                Study the FILE CONTENT — identify the specific line that produces that exception.

                %s

                ## YOUR TASK:
                %s

                ## INSTRUCTIONS:
                - Diagnose from the exception message and stack trace shown above.
                - Find the exact faulty line from the shown code.
                - Do NOT suggest code changes yet. Diagnosis and patch plan only.
                - artifactPath must match: %s

                ## proposedSearchBlock — CRITICAL:
                Copy 3+ lines EXACTLY from the file content above surrounding the bug site.
                Do NOT reconstruct from memory. Do NOT paraphrase.
                This block will be validated against the actual file.

                Respond with ONLY this JSON — no markdown fences, no preamble:

                {
                  "artifactPath": "exact/path/to/file.py",
                  "artifactLine": <integer — the line number of the faulty code>,
                  "rootCauseSummary": "One sentence: what specific code is broken and why it causes the observed exception",
                  "causalExplanation": "How this code path produces the exact exception shown in the failure output",
                  "minimalFixStrategy": "Concrete, specific code change needed — not generic advice",
                  "proposedSearchBlock": "3+ lines copied EXACTLY from the file content shown above",
                  "whyPreviousAttemptsFailed": "Why prior attempts failed, or N/A if first attempt"
                }
                """.formatted(
                context.toString(),
                currentTask,
                artifact != null ? artifact : "(unknown)"
        );
    }

    /**
     * Parse the LLM's raw JSON response into a RootCauseAnalysis.
     * Strips markdown fences if present. Returns invalid sentinel on any parse error.
     *
     * Passes concatenated cached file content to RootCauseAnalysis.of() so the
     * proposedSearchBlock feasibility check can run (normalized comparison).
     */
    private RootCauseAnalysis parseAnalysis(String response, SharedState state) {

        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            JsonNode root = jsonMapper.readTree(json);

            String artifactPath      = textOrNull(root, RootCauseAnalysis.FIELD_ARTIFACT_PATH);
            int    artifactLine      = root.has(RootCauseAnalysis.FIELD_ARTIFACT_LINE)
                                       ? root.get(RootCauseAnalysis.FIELD_ARTIFACT_LINE).asInt(0) : 0;
            String rootCause         = textOrNull(root, RootCauseAnalysis.FIELD_ROOT_CAUSE_SUMMARY);
            String causalExpl        = textOrNull(root, RootCauseAnalysis.FIELD_CAUSAL_EXPLANATION);
            String fixStrategy       = textOrNull(root, RootCauseAnalysis.FIELD_MINIMAL_FIX);
            String prevAttempts      = textOrNull(root, RootCauseAnalysis.FIELD_PREV_ATTEMPTS_EVAL);
            String proposedSearchBlk = textOrNull(root, RootCauseAnalysis.FIELD_PROPOSED_SEARCH_BLOCK);

            // Build concatenated file content for feasibility check.
            // All cached files are checked — the search block just needs to exist in any one.
            String cachedFileContent = buildCachedFileContent(state);

            if (proposedSearchBlk != null) {
                log.info("[Executor] REPAIR_ANALYZE: proposedSearchBlock present ({} chars) — will validate",
                        proposedSearchBlk.length());
            } else {
                log.info("[Executor] REPAIR_ANALYZE: no proposedSearchBlock in response — skipping feasibility check");
            }

            RootCauseAnalysis result = RootCauseAnalysis.of(
                    artifactPath, artifactLine, rootCause, causalExpl, fixStrategy,
                    prevAttempts, proposedSearchBlk,
                    state.getFailingArtifact(), state.getFailingArtifactLine(),
                    cachedFileContent
            );

            // Observability: log artifact path mismatch without blocking.
            // Fix A: path validation is now soft-only — the LLM may correctly identify
            // an upstream causal file that differs from the analyzer's heuristic frame.
            String knownArtifact = state.getFailingArtifact();
            if (artifactPath != null && knownArtifact != null) {
                String normLlm   = artifactPath.replace('\\', '/').replaceAll("^\\./", "").trim();
                String normKnown = knownArtifact.replace('\\', '/').replaceAll("^\\./", "").trim();
                if (!normLlm.endsWith(normKnown) && !normKnown.endsWith(normLlm)) {
                    log.info("[Executor] REPAIR_ANALYZE: artifact path diverges from analyzer heuristic — " +
                             "LLM='{}', analyzer='{}' (allowed — soft check only)", artifactPath, knownArtifact);
                } else {
                    log.info("[Executor] REPAIR_ANALYZE: artifact path matches analyzer: {}", artifactPath);
                }
            }

            return result;

        } catch (Exception e) {
            log.error("[Executor] REPAIR_ANALYZE: JSON parse failed — {}", e.getMessage());
            return RootCauseAnalysis.invalid("JSON parse error: " + e.getMessage());
        }
    }

    /**
     * Concatenate all cached file contents for proposedSearchBlock feasibility check.
     * Returns null if no files are cached (check will be skipped in RootCauseAnalysis).
     */
    private String buildCachedFileContent(SharedState state) {
        if (!state.hasFileContext()) return null;
        Map<String, String> files = state.getRecentFileReads();
        if (files.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String content : files.values()) {
            if (content != null) sb.append(content).append("\n");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        String val = node.get(field).asText(null);
        return (val == null || val.trim().isEmpty()) ? null : val.trim();
    }

    // ====================================================================
    // PROMPT BUILDER
    // ====================================================================

    private String buildExecutionPrompt(String currentTask, SharedState state) {

        StringBuilder context = new StringBuilder();

        if (state.getLastTestResults() != null) {

            TestResults tr = state.getLastTestResults();

            context.append("\n### Test Results:\n");
            context.append(tr.getSummary()).append("\n");

            if (tr.anyFailed() || tr.getFailureType() == TestFailureType.COLLECTION_ERROR) {

                context.append("\n").append(tr.getDetailedFailureSummary());

                if (tr.getFailureType() == TestFailureType.COLLECTION_ERROR) {

                    context.append("""

                        ⚠️ TEST COLLECTION ERROR:
                        Pytest failed during collection (import/syntax error in source code).

                        """);

                    // ✅ Always use sanitized path in prompt — never expose a corrupted
                    // multiline artifact string to the LLM or downstream JSON builders.
                    String artifact     = state.getFailingArtifact();
                    String safeArtifact = sanitizeArtifactPath(artifact);

                    if (safeArtifact != null) {
                        int failLine = state.getFailingArtifactLine();
                        context.append("""
                            Failing artifact: %s%s

                            This file was identified from the stack trace.
                            If it is a SOURCE file: read it and fix the bug directly.
                            If it is a TEST file: read it to understand imports,
                              then identify the broken source file from the traceback
                              and fix THAT file. Do NOT modify test files.

                            """.formatted(
                                safeArtifact,
                                failLine > 0 ? " (error near line " + failLine + ")" : ""
                            ));
                    }

                    context.append("FULL PYTEST OUTPUT:\n");
                    context.append(tr.getRawOutput());
                    context.append("\n");
                }

                String hint = tr.getRepairHint();
                if (hint != null && !hint.isEmpty()) {
                    context.append("\n### Repair Strategy:\n").append(hint).append("\n");
                }

                if (state.getCurrentPhase() == RepairPhase.REPAIR_PATCH &&
                        tr.getFailureType() == TestFailureType.ASSERTION_ERROR) {
                    context.append("""

                        ⚠️ IMPORTANT:
                        - Assertion errors may mean wrong logic OR a missing function
                        - Verify function exists before modifying
                        """);
                }
            }
        }

        if (state.getCurrentPhase() == RepairPhase.REPAIR_PATCH && state.hasFileContext()) {

            context.append("\n### Relevant File Excerpt:\n");
            context.append("(Copy search_block EXACTLY from here — do not guess content)\n\n");

            int failLine = state.getFailingArtifactLine();
            Map<String, String> files = state.getRecentFileReads();

            for (Map.Entry<String, String> entry : files.entrySet()) {
                String path    = entry.getKey();
                String content = entry.getValue();
                String excerpt = extractFileWindow(content, failLine, path);

                context.append("```").append(path).append("\n");
                context.append(excerpt);
                context.append("\n```\n\n");
            }

            log.info("[Executor] Injected {} file(s) into prompt (windowed around line {})",
                    files.size(), failLine);

            // ✅ TOOL ERROR FEEDBACK INJECTION
            // When the previous replace_in_file failed, inject the error message
            // and specific corrective instructions directly into the prompt.
            // This is the only mechanism by which the LLM learns why its last
            // search_block was rejected — without it, it repeats the same mistake.
            String lastToolError = state.getLastToolError();
            if (lastToolError != null) {

                boolean wasAmbiguous = lastToolError.contains("multiple times");
                boolean wasNotFound  = lastToolError.contains("not found");

                context.append("⚠️ PREVIOUS replace_in_file FAILED:\n");
                context.append(lastToolError.length() > 300
                        ? lastToolError.substring(0, 300) + "..."
                        : lastToolError);
                context.append("\n\n");

                if (wasAmbiguous) {
                    context.append("""
                        TO FIX (ambiguous search block):
                        - Your search_block matched more than one location in the file.
                        - Single-line search blocks like "except ImportError:" are FORBIDDEN.
                        - You MUST include at least 3–5 lines of surrounding context so the
                          match is unique. Include the lines immediately before and after
                          the code you want to change.
                        - Copy EXACTLY from the numbered window above (without the "  N | " prefix).

                        """);
                } else if (wasNotFound) {
                    context.append("""
                        TO FIX (search block not found):
                        - Your search_block did not match any text in the file.
                        - You must copy EXACTLY from the file excerpt shown above.
                        - Preserve all indentation and whitespace character-for-character.
                        - Do NOT paraphrase, reconstruct from memory, or invent content.

                        """);
                } else {
                    context.append("""
                        TO FIX:
                        - Copy search_block EXACTLY from the file excerpt above.
                        - Include at least 3 lines of context for a unique match.

                        """);
                }

                log.info("[Executor] Tool error feedback injected (ambiguous={}, notFound={})",
                        wasAmbiguous, wasNotFound);
            }
        }

        // ================================================================
        // ROOT CAUSE ANALYSIS INJECTION (REPAIR_PATCH only)
        // Inject the validated analysis from REPAIR_ANALYZE to ground the patch.
        // The LLM is explicitly required to implement the minimalFixStrategy.
        // ================================================================
        if (state.getCurrentPhase() == RepairPhase.REPAIR_PATCH &&
                state.hasValidRootCauseAnalysis()) {
            RootCauseAnalysis analysis = state.getLastRootCauseAnalysis();
            context.append("\n").append(analysis.toRepairPatchPromptBlock()).append("\n");
            context.append("""

                ⚠️ PATCH GROUNDING RULE:
                Your replace_in_file patch MUST implement the minimalFixStrategy above.
                A patch that does not target the artifact and line identified in the
                root cause analysis will be considered misaligned and rejected.

                """);
            log.info("[Executor] Root cause analysis injected into REPAIR_PATCH prompt");
        }

        String phaseGuidance = getPhaseGuidance(state.getCurrentPhase());

        return """
                You are a CODE REPAIR agent.

                %s

                ## CURRENT PHASE: %s
                %s

                ## CURRENT TASK:
                %s

                ⚠️ CRITICAL: Respond with valid JSON only:

                {
                  "reasoning": "your approach",
                  "tool_calls": [
                    {"tool": "tool_name", "args": {...}}
                  ]
                }

                AVAILABLE TOOLS:
                - read_file: {"path": "relative/path.py"}
                - replace_in_file: {"path": "...", "search_block": "exact match", "replace_block": "replacement"}
                - write_file: {"path": "...", "content": "full content"}
                - run_tests: {"test_file": "path/to/test.py"}
                """.formatted(
                context.toString(),
                state.getCurrentPhase(),
                phaseGuidance,
                currentTask
        );
    }

    /**
     * Extract a windowed excerpt from a file centered on the failure line.
     *
     * WHY: Ollama's context is 4096 tokens. A 1084-line file is ~41k chars and gets
     * silently truncated. The LLM then hallucinates search_block content it never
     * received, producing "Search block not found" or "found multiple times" loops.
     *
     * Each line is prefixed with its real line number so the LLM knows exactly
     * where in the file it is looking.
     *
     * Strategy:
     *   - failLine known: inject CONTEXT_WINDOW_LINES before and after it (~160 lines)
     *   - failLine unknown (-1): inject first MAX_FILE_LINES_FALLBACK lines
     */
    private String extractFileWindow(String content, int failLine, String path) {

        if (content == null) return "";

        String[] lines     = content.split("\n", -1);
        int      totalLines = lines.length;

        int startLine, endLine;

        if (failLine > 0 && failLine <= totalLines) {
            startLine = Math.max(1, failLine - CONTEXT_WINDOW_LINES);
            endLine   = Math.min(totalLines, failLine + CONTEXT_WINDOW_LINES);
        } else {
            startLine = 1;
            endLine   = Math.min(totalLines, MAX_FILE_LINES_FALLBACK);
        }

        StringBuilder sb = new StringBuilder();

        if (startLine > 1) {
            sb.append("# ... (lines 1-").append(startLine - 1).append(" omitted) ...\n");
        }

        for (int i = startLine; i <= endLine; i++) {
            sb.append(String.format("%4d | %s\n", i, lines[i - 1]));
        }

        if (endLine < totalLines) {
            sb.append("# ... (lines ").append(endLine + 1)
              .append("-").append(totalLines).append(" omitted) ...\n");
        }

        log.info("[Executor] File window for {}: lines {}-{} of {} (failLine={})",
                path, startLine, endLine, totalLines, failLine);

        return sb.toString();
    }

    private String getPhaseGuidance(RepairPhase phase) {
        return switch (phase) {
            case REPRODUCE -> """
                    REPRODUCE: Discover structure → Read tests → Run tests.
                    DO NOT modify code.
                    """;

            case REPAIR_ANALYZE -> """
                    REPAIR_ANALYZE: Diagnosis only. NO tool calls. NO code changes.
                    Return raw JSON analysis as instructed.
                    """;

            case REPAIR_PATCH -> """
                    REPAIR_PATCH: Apply the fix described in the Root Cause Analysis above.
                    You MUST call replace_in_file before finishing — read-only is not enough.

                    SEARCH BLOCK RULES (violations cause tool failure):
                    ✅ Must contain AT LEAST 3 lines of code — single-line blocks are FORBIDDEN.
                    ✅ Must include surrounding context lines (lines before and after your target).
                    ✅ Copy EXACTLY from the file excerpt above — no ">>" marker, no padding prefix.
                    ✅ Preserve all original indentation and whitespace character-for-character.
                    ❌ Never use a generic line like "except ImportError:" alone — always include
                       enough context to make the match unique.

                    DO NOT run tests (validation comes next).
                    DO NOT modify test files.
                    """;

            case VALIDATE -> """
                    VALIDATE: Run tests ONLY.
                    No code modifications.
                    """;
        };
    }

    // ====================================================================
    // GATES
    // ====================================================================

    private List<ToolCall> enforceDiscoveryGate(List<ToolCall> calls, SharedState state) {

        if (state.getCurrentPhase() != RepairPhase.REPRODUCE) return calls;
        if (state.isStructureDiscovered()) return calls;

        boolean containsDiscovery = calls.stream().anyMatch(
                c -> "list_files".equals(c.getTool()) || "file_tree".equals(c.getTool())
        );

        if (!containsDiscovery) {
            log.warn("[Executor] Gate: Injecting list_files");
            return List.of(new ToolCall("list_files", "{\"path\": \".\"}"));
        }
        return calls;
    }

    /**
     * REPAIR evidence gate — generic, no hardcoding.
     *
     * Ensures the failing artifact has been read before a patch is attempted.
     * All artifact paths are routed through buildReadFileCall() → sanitizeArtifactPath()
     * before JSON construction — prevents CTRL-CHAR-code-10 Jackson errors from
     * corrupted multiline artifact strings causing an infinite retry loop.
     */
    private List<ToolCall> enforceRepairEvidenceGate(List<ToolCall> calls, SharedState state) {

        if (state.getCurrentPhase() != RepairPhase.REPAIR_PATCH) return calls;

        TestResults lastResults = state.getLastTestResults();
        if (lastResults == null) return calls;

        String artifact = state.getFailingArtifact();
        String safePath = sanitizeArtifactPath(artifact);

        // Assertion error path
        if (lastResults.getFailureType() == TestFailureType.ASSERTION_ERROR) {
            if (safePath != null && !state.hasReadFile(safePath)) {
                log.warn("[Gate] Forcing read of artifact: {}", safePath);
                return buildReadFileCall(artifact);
            }
            return calls;
        }

        // Collection error path
        if (lastResults.getFailureType() != TestFailureType.COLLECTION_ERROR) return calls;

        if (artifact == null) {
            if (!state.isStructureDiscovered()) {
                log.warn("[Gate] No artifact, injecting list_files");
                return List.of(new ToolCall("list_files", "{\"path\": \".\"}"));
            }
            return calls;
        }

        if (safePath == null) {
            log.error("[Gate] Artifact path unrecoverable — falling back to list_files: [{}]", artifact);
            return List.of(new ToolCall("list_files", "{\"path\": \".\"}"));
        }

        if (".".equals(safePath) || "..".equals(safePath)) return calls;

        if (!state.hasReadFile(safePath)) {
            log.warn("[Gate] Artifact not cached — forcing read: {}", safePath);
            return buildReadFileCall(artifact);
        }

        log.info("[Gate] Artifact '{}' already cached — allowing patch.", safePath);
        return calls;
    }

    // ====================================================================
    // ARTIFACT PATH SANITIZATION
    // ====================================================================

    /**
     * Sanitize an artifact path before embedding it in any JSON string or log.
     *
     * ROOT CAUSE THIS FIXES:
     * PytestOutputAnalyzer's FILE_LINE_STANDARD regex historically used [^\"]+
     * which matched newlines (Java character classes match \n by default).
     * This caused captures like:
     *
     *   ">           six.exec_(co, mod.__dict__)\n\nsrc/_pytest/assertion/rewrite.py"
     *
     * When embedded in a JSON string literal this produces a raw newline (code 10),
     * causing Jackson to throw "Illegal unquoted character (CTRL-CHAR, code 10)"
     * on every read_file call — resulting in an infinite retry loop.
     *
     * Returns null if no clean .py path can be recovered.
     */
    private String sanitizeArtifactPath(String artifact) {
        if (artifact == null) return null;

        String trimmed = artifact.trim();

        // Happy path — already a clean single-line .py path
        if (!trimmed.contains("\n") &&
            !trimmed.contains("\r") &&
            !trimmed.contains(">")  &&
            trimmed.endsWith(".py")) {
            return trimmed;
        }

        // Corrupted multiline: scan lines from the end, find last valid .py token
        String[] lines = trimmed.split("[\\r\\n]+");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.endsWith(".py") && !line.contains(">") && !line.contains(" ") && line.length() > 5) {
                log.warn("[Sanitize] Recovered path from corrupted artifact → {}", line);
                return line;
            }
        }

        log.error("[Sanitize] Could not recover .py path from artifact: [{}]", artifact);
        return null;
    }

    /**
     * Build a read_file ToolCall with the artifact path sanitized first.
     *
     * Returns an empty list if the path is unrecoverable, so the Mediator
     * sees a tool-count-of-zero (treated as tool error → RETRY) rather than
     * a JSON parse crash.
     */
    private List<ToolCall> buildReadFileCall(String artifact) {
        String safePath = sanitizeArtifactPath(artifact);
        if (safePath == null) {
            log.error("[Gate] Cannot build read_file — path unrecoverable: [{}]", artifact);
            return List.of();
        }
        log.info("[Gate] Building read_file for: {}", safePath);
        return List.of(new ToolCall("read_file", String.format("{\"path\": \"%s\"}", safePath)));
    }

    // ====================================================================
    // TOOL EXECUTION
    // ====================================================================

    private ExecutionResult executeTools(List<ToolCall> toolCalls, String taskDescription, SharedState state) {

        List<ToolResult> toolResults  = new ArrayList<>();
        List<String>     modifiedFiles = new ArrayList<>();
        TestResults      testResults   = TestResults.notRun();

        for (ToolCall call : toolCalls) {

            log.info("[Executor] Calling tool: {}", call.getTool());
            state.incrementToolCallCount();

            ToolResult result = toolExecutor.execute(call, state);
            toolResults.add(result);

            if (("write_file".equals(call.getTool()) || "replace_in_file".equals(call.getTool()))
                    && result.getExitCode() == 0
                    && result.getTargetFile() != null) {

                modifiedFiles.add(result.getTargetFile());
                log.info("[Executor] File modified: {}", result.getTargetFile());
            }

            if ("run_tests".equals(call.getTool())) {

                String rawStdout = result.getStdout();
                String rawStderr = result.getStderr();
                String combined  = rawStdout + "\n" + rawStderr;

                testResults = parseTestOutput(combined, result.getExitCode(), state);

                if (result.getExitCode() != 0) {

                    log.info("[Executor] Non-zero exit code {} — running analyzer", result.getExitCode());

                    PytestOutputAnalyzer.CollectionFailureAnalysis analysis =
                            pytestAnalyzer.analyze(rawStdout, rawStderr);

                    log.info("[Executor] Analysis: {}", analysis);

                    state.setCollectionFailureSubtype(analysis.getSubtype());
                    state.setFailingArtifact(analysis.getFailingArtifact());
                    state.setCollectionFailureReason(analysis.getReason());

                    // Store line number extracted from the stack trace.
                    // -1 means the analyzer could not find a line number;
                    // ExecutorAgent will fall back to first-N-lines window.
                    state.setFailingArtifactLine(analysis.getFailingLine());
                    if (analysis.getFailingLine() > 0) {
                        log.info("[Signal] failing_artifact_line: {}", analysis.getFailingLine());
                    } else {
                        log.info("[Signal] failing_artifact_line: unknown (will use fallback window)");
                    }

                    if (analysis.getFailingArtifact() != null) {
                        log.info("[Signal] failing_artifact: {}", analysis.getFailingArtifact());
                    } else {
                        log.warn("[Signal] failing_artifact: null");
                    }

                    // ✅ GROUNDING INVARIANT — hard enforcement
                    // After artifact is identified, immediately cache it.
                    // If caching fails, abort: REPAIR_ANALYZE must never run without
                    // cached file content. Returning an error ExecutionResult here
                    // causes Mediator to see a tool error and trigger RETRY/REPLAN.
                    String failingArtifact = state.getFailingArtifact();
                    if (failingArtifact != null && !state.hasReadFile(failingArtifact)) {
                        log.info("[GROUNDING] Artifact identified but not cached. Reading file: {}",
                                failingArtifact);
                        ToolCall readFileCall = new ToolCall("read_file",
                                String.format("{\"path\": \"%s\"}", failingArtifact));
                        ToolResult readResult = toolExecutor.execute(readFileCall, state);
                        if (readResult.getExitCode() == 0) {
                            log.info("[GROUNDING] Artifact cached successfully");
                        } else {
                            log.error("[GROUNDING] Failed to cache artifact — aborting REPRODUCE phase");
                            return ExecutionResult.error("Grounding failed: unable to cache failing artifact");
                        }
                    }
                }

                log.info("[Executor] Tests: {}", testResults.getSummary());
            }
        }

        return new ExecutionResult(taskDescription, toolResults, testResults, modifiedFiles);
    }

    private TestResults parseTestOutput(String output, int exitCode, SharedState state) {

        if (output == null || output.isEmpty()) return TestResults.notRun();

        TestFailureType type;

        if      (exitCode == 0)                    type = TestFailureType.NONE;
        else if (exitCode == 1)                    type = TestFailureType.ASSERTION_ERROR;
        else if (exitCode == 2)                  { log.info("[Executor] Exit code 2 → COLLECTION ERROR");
                                                   type = TestFailureType.COLLECTION_ERROR; }
        else if (exitCode == 4 || exitCode == 5)   type = TestFailureType.COLLECTION_ERROR;
        else                                     { log.warn("[Executor] Unknown exit code {} → ASSERTION_ERROR", exitCode);
                                                   type = TestFailureType.ASSERTION_ERROR; }

        List<String> passing = new ArrayList<>();
        List<String> failing = new ArrayList<>();

        Pattern pattern = Pattern.compile("([^:]+::[^\\s]+)\\s+(PASSED|FAILED)");
        Matcher matcher = pattern.matcher(output);

        while (matcher.find()) {
            String name   = matcher.group(1);
            String status = matcher.group(2);
            if ("PASSED".equals(status)) passing.add(name);
            else failing.add(name);
        }

        String snippet = (!failing.isEmpty() || (passing.isEmpty() && failing.isEmpty()))
                ? extractErrorSnippet(output) : null;

        return new TestResults(passing, failing, output, type, snippet);
    }

    private String extractErrorSnippet(String output) {

        String[] lines = output.split("\n");
        StringBuilder snippet = new StringBuilder();
        int count = 0;

        for (String line : lines) {
            if (line.contains("Error") || line.contains("FAILED") || line.contains("assert")) {
                snippet.append(line).append("\n");
                if (++count >= 5) break;
            }
        }
        return snippet.toString().trim();
    }

    private String retryWithJsonEnforcement(String originalPrompt, String badResponse) {

        String retryPrompt = originalPrompt + """


                ⚠️ JSON PARSE FAILURE - RETRY ⚠️

                Common issue: unescaped newlines in strings.

                ✅ CORRECT: Escape newlines as \\n
                {
                  "tool_calls": [{"tool": "replace_in_file", "args": {
                    "search_block": "def foo():\\n    pass"
                  }}]
                }

                Return ONLY corrected JSON now:
                """;

        return llmClient.generateWithRole(
                AgentType.EXECUTOR,
                retryPrompt,
                llmClient.getTemperatureForRole(AgentType.EXECUTOR)
        );
    }

    private List<ToolCall> filterToolsByPhase(List<ToolCall> toolCalls, SharedState state) {

        RepairPhase  phase   = state.getCurrentPhase();
        List<String> allowed = getAllowedTools(phase);
        List<ToolCall> filtered = new ArrayList<>();

        for (ToolCall call : toolCalls) {
            if (allowed.contains(call.getTool())) {
                filtered.add(call);
            } else {
                log.warn("[Executor] BLOCKED tool '{}' in phase {}", call.getTool(), phase);
            }
        }
        return filtered;
    }

    private List<String> getAllowedTools(RepairPhase phase) {
        return switch (phase) {
            case REPRODUCE      -> List.of("read_file", "run_tests", "grep", "list_files", "file_tree");
            case REPAIR_ANALYZE -> List.of();  // Structurally no tools — raw JSON response only
            case REPAIR_PATCH   -> List.of("read_file", "replace_in_file", "write_file", "grep", "list_files", "file_tree");
            case VALIDATE       -> List.of("run_tests");
        };
    }

    // ====================================================================
    // JSON PARSING
    // ====================================================================

    private static class ParseResult {
        final List<ToolCall> calls;
        final boolean failedWithJson;
        ParseResult(List<ToolCall> calls, boolean failedWithJson) {
            this.calls = calls;
            this.failedWithJson = failedWithJson;
        }
    }

    private ParseResult parseToolCallsFromResponse(String response) {

        List<ToolCall> calls = new ArrayList<>();

        try {
            String jsonText = extractJson(response);
            JsonNode root = jsonMapper.readTree(jsonText);

            if (root == null || !root.has("tool_calls")) return new ParseResult(calls, false);

            JsonNode toolCallsNode = root.get("tool_calls");
            if (!toolCallsNode.isArray()) return new ParseResult(calls, false);

            for (JsonNode callNode : toolCallsNode) {
                if (callNode == null) continue;
                JsonNode toolNode = callNode.get("tool");
                if (toolNode == null || toolNode.isNull()) continue;
                String tool = toolNode.asText();
                if (tool == null || tool.trim().isEmpty()) continue;
                JsonNode argsNode = callNode.get("args");
                String args = (argsNode == null || argsNode.isNull()) ? "{}" : argsNode.toString();
                calls.add(new ToolCall(tool, args));
            }

            return new ParseResult(calls, false);

        } catch (Exception e) {
            log.error("[Executor] Parse failed: {}", e.getMessage());
            return new ParseResult(calls, true);
        }
    }

    private String extractJson(String text) {
        Pattern p = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
        Matcher m = p.matcher(text);
        if (m.find()) return m.group(1).trim();
        return text.trim();
    }

    public static class ToolCall {
        private final String tool;
        private final String args;

        public ToolCall(String tool, String args) {
            this.tool = tool;
            this.args = args;
        }

        public String getTool() { return tool; }
        public String getArgs() { return args; }
    }

    @Override
    public void handleTask(String taskId) { /* Not used */ }
}