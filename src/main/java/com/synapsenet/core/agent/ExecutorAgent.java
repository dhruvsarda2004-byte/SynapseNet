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

    // ✅ AUTHORITATIVE BLOCK: Lines above + below artifactLine for deterministic
    // search block extraction. Deliberately separate from CONTEXT_WINDOW_LINES —
    // different purpose, different constraint.
    //
    // 5 lines above + target line + 5 lines below = ~11 lines total.
    // Small enough to avoid duplicate collisions.
    // Large enough to include structural uniqueness (indentation + control flow).
    // Stable even if the bug is inside a short function.
    // Do not exceed 8. Do not go below 3.
    private static final int AUTHORITATIVE_BLOCK_RADIUS = 5;

    private final LLMClient llmClient;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper jsonMapper;
    private final PytestOutputAnalyzer pytestAnalyzer;

    public ExecutorAgent(LLMClient llmClient, ToolExecutor toolExecutor,
                         PytestOutputAnalyzer pytestAnalyzer) {
        this.llmClient    = llmClient;
        this.toolExecutor = toolExecutor;
        this.jsonMapper   = new ObjectMapper();
        this.pytestAnalyzer = pytestAnalyzer;
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

        // STATE SNAPSHOT before REPAIR_ANALYZE validation
        log.info("[STATE SNAPSHOT] artifact={}, line={}, cachedFiles={}",
                 state.getFailingArtifact(),
                 state.getFailingArtifactLine(),
                 state.getRecentFileReads().keySet());

        log.info("[Executor] REPAIR_ANALYZE prompt length: {}", prompt.length());
        log.info("[Executor] REPAIR_ANALYZE: workspaceFiles.size()={}", state.getWorkspaceFiles().size());

        String llmResponse = llmClient.generateWithRole(
                AgentType.EXECUTOR,
                prompt,
                llmClient.getTemperatureForRole(AgentType.EXECUTOR)
        );

        log.info("[Executor] REPAIR_ANALYZE response received ({} chars)", llmResponse.length());

        // ── Pre-cache: extract artifactPath from raw JSON BEFORE validation ──
        //
        // Why: parseAnalysis() validates proposedSearchBlock against the file
        // cache. If the LLM correctly diagnosed a cross-file artifact that isn't
        // cached yet, validation runs against the wrong file (test file only).
        // The block passes vacuously, REPAIR_PATCH gets no source content, LLM
        // patches blind, replace_in_file fails.
        //
        // Fix: pre-extract the path, try to cache it NOW, then call parseAnalysis()
        // so proposedSearchBlock is validated against real file content.
        //
        // IMPORTANT: pre-cache failure is a WARNING, not an abort.
        // The LLM may hallucinate a non-existent path on the first attempt.
        // If the file doesn't exist, fall through to parseAnalysis() — it will
        // mark the analysis invalid — then re-prompt so the LLM can correct itself
        // using the workspace file index. Only abort after retry also fails.
        String preExtractedArtifact = preExtractArtifactPath(llmResponse, state);
        if (preExtractedArtifact != null && !state.hasReadFile(preExtractedArtifact)) {
            String _known = state.getFailingArtifact();
            boolean _isDifferent = _known == null
                    || (!preExtractedArtifact.equals(_known)
                        && !preExtractedArtifact.endsWith(_known)
                        && !_known.endsWith(preExtractedArtifact));
            if (_isDifferent) {
                log.info("[Executor] REPAIR_ANALYZE: Pre-caching '{}' before validation",
                         preExtractedArtifact);
                ToolCall _rc = new ToolCall("read_file",
                        String.format("{\"path\": \"%s\"}", preExtractedArtifact));
                ToolResult _rr = toolExecutor.execute(_rc, state);
                if (_rr.getExitCode() == 0) {
                    log.info("[Executor] REPAIR_ANALYZE: Pre-cache succeeded — " +
                             "proposedSearchBlock will validate against real file");
                } else {
                    // File doesn't exist — LLM hallucinated a path.
                    // Do NOT abort. Fall through and let the retry re-prompt
                    // with the workspace file index so the LLM corrects itself.
                    log.warn("[Executor] REPAIR_ANALYZE: Pre-cache failed — '{}' not in workspace. " +
                             "Will re-prompt so LLM can pick correct path from file index.",
                             preExtractedArtifact);
                }
            }
        }

        // Full parse + proposedSearchBlock validation (source file in cache if pre-cache succeeded)
        RootCauseAnalysis analysis = parseAnalysis(llmResponse, state);

        log.info("[ANALYZE RESULT] artifactPath={}, artifactLine={}",
                 analysis.getArtifactPath(),
                 analysis.getArtifactLine());

        // ── Retry: re-prompt if analysis is invalid ───────────────────────────
        // Covers two cases:
        //   (a) LLM hallucinated a path (pre-cache failed) → re-prompt with file index
        //   (b) proposedSearchBlock didn't match real file → re-prompt with file visible
        // Only abort with EXTERNAL_ARTIFACT if retry also proposes a missing path.
        if (!analysis.isValid()) {
            String llmArtifact   = analysis.getArtifactPath();
            String knownArtifact = state.getFailingArtifact();

            boolean isDifferentFile = llmArtifact != null
                    && (knownArtifact == null || (!llmArtifact.equals(knownArtifact)
                        && !llmArtifact.endsWith(knownArtifact)
                        && !knownArtifact.endsWith(llmArtifact)));

            // Try to cache it if not already cached (may already be there from pre-cache)
            if (isDifferentFile && llmArtifact != null && !state.hasReadFile(llmArtifact)) {
                log.info("[Executor] REPAIR_ANALYZE: Retry — attempting to cache '{}'", llmArtifact);
                ToolCall readFileCall = new ToolCall("read_file",
                        String.format("{\"path\": \"%s\"}", llmArtifact));
                ToolResult readResult = toolExecutor.execute(readFileCall, state);
                if (readResult.getExitCode() != 0) {
                    // Still not found after retry attempt — now it's safe to abort
                    log.error("[Executor] REPAIR_ANALYZE: '{}' still not in workspace after retry → EXTERNAL_ARTIFACT",
                              llmArtifact);
                    return ExecutionResult.error("EXTERNAL_ARTIFACT: " + llmArtifact);
                }
            }

            // Re-prompt with source file now visible (or with corrected file index guidance)
            log.info("[Executor] REPAIR_ANALYZE: Re-prompting — source file in cache: {}",
                     state.getRecentFileReads().keySet());
            String retryPrompt = buildRepairAnalyzePrompt(currentTask, state);
            String retryResponse = llmClient.generateWithRole(
                    AgentType.EXECUTOR,
                    retryPrompt,
                    llmClient.getTemperatureForRole(AgentType.EXECUTOR)
            );
            log.info("[Executor] REPAIR_ANALYZE: retry response ({} chars)", retryResponse.length());

            // Pre-cache the retry artifact too (LLM may have corrected to a different path)
            String retryArtifact = preExtractArtifactPath(retryResponse, state);
            if (retryArtifact != null && !state.hasReadFile(retryArtifact)) {
                ToolCall rc2 = new ToolCall("read_file",
                        String.format("{\"path\": \"%s\"}", retryArtifact));
                ToolResult rr2 = toolExecutor.execute(rc2, state);
                if (rr2.getExitCode() != 0) {
                    log.error("[Executor] REPAIR_ANALYZE: Retry artifact '{}' not in workspace → EXTERNAL_ARTIFACT",
                              retryArtifact);
                    return ExecutionResult.error("EXTERNAL_ARTIFACT: " + retryArtifact);
                }
                log.info("[Executor] REPAIR_ANALYZE: Retry artifact '{}' cached", retryArtifact);
            }

            analysis = parseAnalysis(retryResponse, state);
            log.info("[ANALYZE RESULT retry] artifactPath={}, artifactLine={}",
                     analysis.getArtifactPath(), analysis.getArtifactLine());
        }

        // ── Grounding: ensure diagnosed artifact is readable BEFORE storing ──
        //
        // VALIDATION ORDERING FIX:
        // state mutation happens AFTER full validation completes — never before.
        // If the artifact cannot be read, the analysis is invalidated (not aborted),
        // so the Mediator sees hasValidRootCauseAnalysis()==false and routes to RETRY.
        if (analysis.isValid()) {
            String diagnosedArtifact = analysis.getArtifactPath();
            if (diagnosedArtifact != null && !state.hasReadFile(diagnosedArtifact)) {
                log.info("[Executor] REPAIR_ANALYZE: Final cache for '{}'", diagnosedArtifact);
                ToolCall rc = new ToolCall("read_file",
                        String.format("{\"path\": \"%s\"}", diagnosedArtifact));
                ToolResult rr = toolExecutor.execute(rc, state);
                if (rr.getExitCode() != 0) {
                    // Grounding failed — invalidate BEFORE storing so the Mediator
                    // can route to RETRY rather than seeing a stored-but-broken analysis.
                    log.warn("[Executor] REPAIR_ANALYZE: '{}' unresolvable — invalidating analysis",
                             diagnosedArtifact);
                    analysis = RootCauseAnalysis.invalid(
                            "Artifact path unresolvable in workspace: " + diagnosedArtifact);
                }
            }

            // ── Authoritative block extraction ────────────────────────────────
            // Grounding has confirmed the file is readable and cached.
            // Extract a deterministic search block centered on artifactLine.
            //
            // This block comes from the actual file — the LLM cannot hallucinate it.
            // It is stored on the analysis and injected into REPAIR_PATCH as the
            // mandatory search_block. The LLM only controls replace_block.
            //
            // Done here (after grounding, before storing) so:
            //   - The block is guaranteed to match real file content.
            //   - REPLAN cycles preserve it (stored in RootCauseAnalysis, not transient).
            //   - No recomputation inconsistency.
            if (analysis.isValid()) {
                String diagnosedPath    = analysis.getArtifactPath();
                int    diagnosedLine    = analysis.getArtifactLine();
                String fileContent      = state.getRecentFileReads().get(diagnosedPath);

                // Handle path normalization variations (e.g. ./ratelimiter/bucket.py vs ratelimiter/bucket.py)
                if (fileContent == null) {
                    for (Map.Entry<String, String> entry : state.getRecentFileReads().entrySet()) {
                        String key = entry.getKey();
                        if (key.endsWith(diagnosedPath.replaceAll("^\\./", "")) ||
                            diagnosedPath.replaceAll("^\\./", "").equals(key.replaceAll("^\\./", ""))) {
                            fileContent = entry.getValue();
                            break;
                        }
                    }
                }

                if (fileContent != null && diagnosedLine > 0) {
                    String authBlock = extractAuthoritativeBlock(fileContent, diagnosedLine);
                    analysis = analysis.withAuthoritativeSearchBlock(authBlock);
                    log.info("[Executor] REPAIR_ANALYZE: Authoritative block extracted — " +
                             "{} lines centered on line {} of {}",
                             authBlock.split("\n", -1).length, diagnosedLine, diagnosedPath);
                } else {
                    log.warn("[Executor] REPAIR_ANALYZE: Could not extract authoritative block — " +
                             "fileContent={}, diagnosedLine={}",
                             fileContent != null ? "present" : "null", diagnosedLine);
                }

                log.info("[Executor] REPAIR_ANALYZE: Grounding complete — cache={}",
                         state.getRecentFileReads().keySet());
            }
        }

        // State mutation happens AFTER full validation — never before.
        // Mediator reads state.hasValidRootCauseAnalysis() directly; storing an
        // invalid analysis here causes a natural RETRY rather than an ABORTED transition.
        state.setRootCauseAnalysis(analysis);

        if (analysis.isValid()) {
            log.info("[Executor] REPAIR_ANALYZE: Valid analysis stored — {}", analysis.getRootCauseSummary());
            log.info("[Executor] REPAIR_ANALYZE: authoritativeBlock={}, proposedBlock={}",
                     analysis.hasAuthoritativeSearchBlock() ? "present" : "absent",
                     analysis.getProposedSearchBlock() != null ? "present" : "absent");
        } else {
            log.warn("[Executor] REPAIR_ANALYZE: Analysis invalid — {}", analysis.getInvalidReason());
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

    // ====================================================================
    // AUTHORITATIVE BLOCK EXTRACTION
    // ====================================================================

    /**
     * Extract a deterministic search block centered on targetLine.
     *
     * Uses AUTHORITATIVE_BLOCK_RADIUS lines above and below targetLine.
     * Total window ≈ 2 * AUTHORITATIVE_BLOCK_RADIUS + 1 lines.
     *
     * Why this constant exists separately from CONTEXT_WINDOW_LINES:
     *   - CONTEXT_WINDOW_LINES is for reasoning context (LLM needs to see surroundings).
     *   - AUTHORITATIVE_BLOCK_RADIUS is for deterministic matching (must be unique, not large).
     *   Different purpose → different constraint.
     *
     * NO omission markers are added (unlike extractFileWindow).
     * The output must be a clean verbatim slice of the file with no decorations,
     * so replace_in_file can match it byte-for-byte.
     */
    private String extractAuthoritativeBlock(String content, int targetLine) {
        if (content == null || targetLine <= 0) return null;

        String[] lines      = content.split("\n", -1);
        int      totalLines = lines.length;

        if (targetLine > totalLines) {
            log.warn("[Executor] extractAuthoritativeBlock: targetLine={} exceeds totalLines={} — clamping",
                     targetLine, totalLines);
            targetLine = totalLines;
        }

        int startLine = Math.max(1, targetLine - AUTHORITATIVE_BLOCK_RADIUS);
        int endLine   = Math.min(totalLines, targetLine + AUTHORITATIVE_BLOCK_RADIUS);

        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            sb.append(lines[i - 1]).append("\n");
        }

        log.info("[Executor] extractAuthoritativeBlock: lines {}-{} of {} (radius={}, target={})",
                 startLine, endLine, totalLines, AUTHORITATIVE_BLOCK_RADIUS, targetLine);

        return sb.toString();
    }

    // ====================================================================
    // REPAIR_ANALYZE PROMPT BUILDER
    // ====================================================================

    /**
     * Build the REPAIR_ANALYZE prompt.
     *
     * No tool definitions exposed. Instructs the LLM to return ONLY raw JSON.
     * Injects file window, failure context, and (on replan cycles) prior analysis.
     *
     * Change 1: Raw pytest failure output injected (exception type, message, stack frames).
     * Change 2: Prior failed diagnoses injected. LLM instructed to materially differ.
     * Change 3: proposedSearchBlock field in JSON schema — informational, not for matching.
     * Change 4: No line-number anchor in prompt framing.
     */
    private String buildRepairAnalyzePrompt(String currentTask, SharedState state) {

        StringBuilder context = new StringBuilder();

        String artifact = state.getFailingArtifact();
        int    failLine = state.getFailingArtifactLine();

        // ── Raw pytest failure output ─────────────────────────────────────────
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
                context.append("=== FAILURE SUMMARY ===\n");
                context.append(lastResults.getDetailedFailureSummary());
                context.append("\n=== END FAILURE SUMMARY ===\n\n");
            }
        }

        if (artifact != null) {
            context.append("Failing file: ").append(artifact).append("\n\n");
        }

        // ── Structured failing line injection (CRITICAL FIX) ─────────────────────
        if (artifact != null && failLine > 0) {
            context.append("Failing artifact line: ")
                   .append(failLine)
                   .append("\n\n");
            log.info("[Executor] REPAIR_ANALYZE: injected failing artifact line into prompt: {}", failLine);
        }

        // ── CHANGE 1: Workspace file index injection (replaces file tree) ────────
        List<String> workspaceFiles = state.getWorkspaceFiles();
        if (workspaceFiles != null && !workspaceFiles.isEmpty()) {
            context.append("=== WORKSPACE FILES (Authoritative Index) ===\n");
            context.append("These are ALL files in the workspace. You MUST select one by index.\n\n");
            for (int i = 0; i < workspaceFiles.size(); i++) {
                context.append(i).append(": ").append(workspaceFiles.get(i)).append("\n");
            }
            context.append("\n=== END WORKSPACE FILES ===\n\n");
            log.info("[Executor] REPAIR_ANALYZE: injected indexed workspace file list ({} files)", workspaceFiles.size());
        } else {
            log.warn("[Executor] REPAIR_ANALYZE: no workspace file list in state — LLM will guess paths");
        }

        // ── File window ───────────────────────────────────────────────────────
        if (artifact != null && state.hasReadFile(artifact)) {
            Map<String, String> files = state.getRecentFileReads();
            String artifactContent = files.get(artifact);

            if (artifactContent == null) {
                for (String key : files.keySet()) {
                    if (key.endsWith(artifact) || artifact.endsWith(key)) {
                        artifactContent = files.get(key);
                        artifact = key;
                        break;
                    }
                }
            }

            if (artifactContent != null) {
                context.append("=== FILE CONTENT (test file where pytest reported the error) ===\n");
                context.append("NOTE: The bug may NOT be in this file — the real fault is likely\n");
                context.append("in a project source file referenced in the stack trace.\n\n");
                String excerpt = extractFileWindow(artifactContent, failLine, artifact);
                context.append("File: ").append(artifact).append("\n");
                context.append("```python\n");
                context.append(excerpt);
                context.append("\n```\n\n");
                context.append("=== END FILE CONTENT ===\n\n");
                log.info("[Executor] REPAIR_ANALYZE: Injected file window for {}: lines around {}",
                         artifact, failLine);
            }

            // Also inject any other cached files (e.g. source files cached on retry)
            Map<String, String> allFiles = state.getRecentFileReads();
            for (Map.Entry<String, String> entry : allFiles.entrySet()) {
                String cachedPath = entry.getKey();
                if (cachedPath.equals(artifact)) continue;
                String cachedContent = entry.getValue();
                if (cachedContent == null) continue;
                context.append("=== FILE CONTENT (source file) ===\n");
                context.append("File: ").append(cachedPath).append("\n");
                context.append("```python\n");
                context.append(extractFileWindow(cachedContent, -1, cachedPath));
                context.append("\n```\n\n");
                context.append("=== END FILE CONTENT ===\n\n");
                log.info("[Executor] REPAIR_ANALYZE: Also injected cached source file: {}", cachedPath);
            }
        } else if (artifact != null) {
            context.append("Note: File content for ").append(artifact)
                    .append(" not yet cached.\n");
            context.append("Use the exception output above to identify the faulty line.\n\n");
            log.warn("[Executor] REPAIR_ANALYZE: Failing artifact not cached — {}", artifact);
        }

        // ── Prior failed diagnoses ────────────────────────────────────────────
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
                - The reported failing artifact is: %s — but this is only where pytest
                  surfaced the error. The actual bug may be in a source file referenced
                  in the stack trace. Identify the real bug site.
                - Set artifactIndex to the index of the SOURCE FILE that contains the bug
                  (from the WORKSPACE FILES list above), not necessarily the test file.
                - Find the exact faulty line from the exception output and stack frames.
                - Do NOT suggest code changes yet. Diagnosis and patch plan only.

                ## proposedSearchBlock — INFORMATIONAL ONLY:
                Copy 3+ lines from the file content above that you believe surround the bug.
                This is for traceability only — the system will extract the authoritative
                search block automatically. Your copy does NOT need to be exact.

                ## JSON FORMAT RULES — CRITICAL:
                - All string values must escape newlines as \\n. NEVER embed a literal newline
                  character inside a JSON string value (CTRL-CHAR code 10 causes a parse error).
                - Example: "proposedSearchBlock": "def foo():\\n    return bar"

                Respond with ONLY this JSON — no markdown fences, no preamble:

                {
                  "artifactIndex": <integer — index from the WORKSPACE FILES list above>,
                  "artifactLine": <integer — the line number of the faulty code>,
                  "rootCauseSummary": "One sentence: what specific code is broken and why it causes the observed exception",
                  "causalExplanation": "How this code path produces the exact exception shown in the failure output",
                  "minimalFixStrategy": "Concrete, specific code change needed — not generic advice",
                  "proposedSearchBlock": "3+ lines from the file content above surrounding the bug site",
                  "whyPreviousAttemptsFailed": "Why prior attempts failed, or N/A if first attempt"
                }
                """.formatted(
                context.toString(),
                currentTask,
                artifact != null ? artifact : "(unknown)"
        );
    }

    /**
     * Lightweight pre-extraction of artifactPath from raw LLM JSON response.
     * Tries artifactIndex first (resolved against workspace file list), then
     * falls back to the legacy artifactPath string field.
     *
     * Called BEFORE full parseAnalysis() so the diagnosed source file can be
     * cached before proposedSearchBlock validation runs.
     *
     * Returns null on any parse failure — non-fatal, full parseAnalysis() handles errors.
     */
    private String preExtractArtifactPath(String response, SharedState state) {
        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\n?", "").replaceAll("```$", "").trim();
            }
            JsonNode root = jsonMapper.readTree(json);

            // ── CHANGE 4: Try index resolution first ──────────────────────────
            List<String> wsFiles = state.getWorkspaceFiles();
            if (root.has("artifactIndex") && wsFiles != null && !wsFiles.isEmpty()) {
                int idx = root.get("artifactIndex").asInt(-1);
                if (idx >= 0 && idx < wsFiles.size()) {
                    String path = wsFiles.get(idx);
                    log.info("[Executor] REPAIR_ANALYZE: Pre-extracted artifactIndex={} → '{}'", idx, path);
                    return path;
                } else {
                    log.warn("[Executor] REPAIR_ANALYZE: Pre-extract artifactIndex={} out of bounds (size={})",
                             idx, wsFiles != null ? wsFiles.size() : 0);
                }
            }

            // Fallback: legacy string path field
            String path = textOrNull(root, RootCauseAnalysis.FIELD_ARTIFACT_PATH);
            if (path != null) {
                path = path.replaceAll("^\\./", ""); // normalize ./ prefix
                log.warn("[Executor] REPAIR_ANALYZE: Pre-extracted legacy artifactPath='{}'", path);
            }
            return path;
        } catch (Exception e) {
            log.debug("[Executor] REPAIR_ANALYZE: Pre-extract failed (non-fatal): {}", e.getMessage());
            return null;
        }
    }

    private RootCauseAnalysis parseAnalysis(String response, SharedState state) {

        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }

            JsonNode root = jsonMapper.readTree(json);

            // ── CHANGE 3: Resolve artifactIndex → path, fall back to string field ──
            String artifactPath = null;
            List<String> wsFiles = state.getWorkspaceFiles();
            if (root.has("artifactIndex") && wsFiles != null && !wsFiles.isEmpty()) {
                int idx = root.get("artifactIndex").asInt(-1);
                if (idx >= 0 && idx < wsFiles.size()) {
                    artifactPath = wsFiles.get(idx);
                    log.info("[Executor] REPAIR_ANALYZE: artifactIndex={} resolved to '{}'", idx, artifactPath);
                } else {
                    log.warn("[Executor] REPAIR_ANALYZE: artifactIndex={} out of bounds (size={})",
                             idx, wsFiles.size());
                }
            } else {
                artifactPath = textOrNull(root, RootCauseAnalysis.FIELD_ARTIFACT_PATH);
                if (artifactPath != null)
                    log.warn("[Executor] REPAIR_ANALYZE: no artifactIndex — fell back to string path '{}'", artifactPath);
            }

            int    artifactLine      = root.has(RootCauseAnalysis.FIELD_ARTIFACT_LINE)
                                       ? root.get(RootCauseAnalysis.FIELD_ARTIFACT_LINE).asInt(0) : 0;
            String rootCause         = textOrNull(root, RootCauseAnalysis.FIELD_ROOT_CAUSE_SUMMARY);
            String causalExpl        = textOrNull(root, RootCauseAnalysis.FIELD_CAUSAL_EXPLANATION);
            String fixStrategy       = textOrNull(root, RootCauseAnalysis.FIELD_MINIMAL_FIX);
            String prevAttempts      = textOrNull(root, RootCauseAnalysis.FIELD_PREV_ATTEMPTS_EVAL);
            String proposedSearchBlk = textOrNull(root, RootCauseAnalysis.FIELD_PROPOSED_SEARCH_BLOCK);

            String cachedFileContent = buildCachedFileContent(state);

            if (proposedSearchBlk != null) {
                log.info("[Executor] REPAIR_ANALYZE: proposedSearchBlock present ({} chars) — informational only",
                        proposedSearchBlk.length());
            }

            RootCauseAnalysis result = RootCauseAnalysis.of(
                    artifactPath, artifactLine, rootCause, causalExpl, fixStrategy,
                    prevAttempts, proposedSearchBlk,
                    state.getFailingArtifact(), state.getFailingArtifactLine(),
                    cachedFileContent
            );

            // Observability: log artifact path mismatch without blocking.
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
     * Returns null if no files are cached.
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
            context.append("(The code below is shown exactly as it appears in the file.\n");
            context.append(" The authoritative search block below was extracted from this content.)\n\n");

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

            // Tool error feedback injection
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
                        - You MUST use the authoritative search block provided below — do not modify it.

                        """);
                } else if (wasNotFound) {
                    context.append("""
                        TO FIX (search block not found):
                        - Your search_block did not match. You MUST use the authoritative
                          search block provided below — copy it character-for-character.
                        - The search_block is a LITERAL string. Do NOT use regex, backslash
                          escapes, \\s, \\., \\n, or r'' prefix. Whitespace must match exactly.

                        """);
                } else {
                    context.append("""
                        TO FIX:
                        - Use the authoritative search block provided below exactly as shown.
                        - The search_block must be a literal string — no regex, no escape sequences.

                        """);
                }

                log.info("[Executor] Tool error feedback injected (ambiguous={}, notFound={})",
                        wasAmbiguous, wasNotFound);
            }
        }

        // ================================================================
        // ROOT CAUSE ANALYSIS INJECTION (REPAIR_PATCH only)
        // ================================================================
        if (state.getCurrentPhase() == RepairPhase.REPAIR_PATCH &&
                state.hasValidRootCauseAnalysis()) {
            RootCauseAnalysis analysis = state.getLastRootCauseAnalysis();
            context.append("\n").append(analysis.toRepairPatchPromptBlock()).append("\n");
            context.append("""

                ⚠️ PATCH GROUNDING RULES:
                1. Your search_block MUST be copied CHARACTER-FOR-CHARACTER from the
                   AUTHORITATIVE SEARCH BLOCK above. Do NOT modify a single character.
                2. Only your replace_block is under your control.
                3. A patch that alters the search_block will fail immediately.
                4. The search_block is a RAW LITERAL STRING — no regex, no backslash
                   escapes, no \\s, no \\., no \\n. Spaces and newlines must appear exactly
                   as they do in the file. The tool performs plain string matching only.

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
     * Used for reasoning context injection (LLM needs to see surroundings).
     * NOT used for search block matching — see extractAuthoritativeBlock().
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
            sb.append("# ... (lines 1-").append(startLine - 1).append(" omitted — do not include this marker in search_block) ...\n");
        }

        for (int i = startLine; i <= endLine; i++) {
            sb.append(lines[i - 1]).append("\n");
        }

        if (endLine < totalLines) {
            sb.append("# ... (lines ").append(endLine + 1)
              .append("-").append(totalLines).append(" omitted — do not include this marker in search_block) ...\n");
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

            // ── CHANGE 1: Added three ❌ lines explicitly prohibiting regex syntax ──
            case REPAIR_PATCH -> """
                    REPAIR_PATCH: Apply the fix described in the Root Cause Analysis above.
                    You MUST call replace_in_file before finishing — read-only is not enough.

                    SEARCH BLOCK RULES (violations cause immediate tool failure):
                    ✅ Use the AUTHORITATIVE SEARCH BLOCK provided above — copy it exactly.
                    ✅ Do NOT add comments, do NOT alter indentation, do NOT paraphrase.
                    ✅ Only your replace_block is under your control.
                    ❌ Do NOT construct your own search_block — use the one provided.
                    ❌ Do NOT use regex syntax. No \\s, \\., \\n, \\t or any backslash escapes.
                    ❌ Do NOT prefix with r'' or use any escape sequences.
                    ❌ The search_block is a LITERAL string — whitespace and newlines must match exactly.

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

    private String sanitizeArtifactPath(String artifact) {
        if (artifact == null) return null;

        String trimmed = artifact.trim();

        if (!trimmed.contains("\n") &&
            !trimmed.contains("\r") &&
            !trimmed.contains(">")  &&
            trimmed.endsWith(".py")) {
            return trimmed;
        }

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

        List<ToolResult> toolResults   = new ArrayList<>();
        List<String>     modifiedFiles = new ArrayList<>();
        TestResults      testResults   = TestResults.notRun();
        int              rawTestExitCode = -1;

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

            if (("file_tree".equals(call.getTool()) || "list_files".equals(call.getTool()))
                    && result.getExitCode() == 0
                    && result.getStdout() != null
                    && !result.getStdout().isBlank()) {
                state.setWorkspaceFileTree(result.getStdout());
                log.info("[Executor] Workspace file tree captured ({} chars) from {}",
                        result.getStdout().length(), call.getTool());
            }

            if ("run_tests".equals(call.getTool())) {

                if (rawTestExitCode != -1) {
                    log.warn("[Executor] run_tests already executed this iteration — skipping duplicate call");
                    continue;
                }

                String rawStdout = result.getStdout();
                String rawStderr = result.getStderr();
                String combined  = rawStdout + "\n" + rawStderr;

                System.out.println("\n===== RAW PYTEST OUTPUT START =====");
                System.out.println(combined);
                System.out.println("===== RAW PYTEST OUTPUT END =====\n");

                rawTestExitCode = result.getExitCode();
                log.info("[Executor] run_tests rawTestExitCode captured: {}", rawTestExitCode);

                try {
                    testResults = parseTestOutput(combined, result.getExitCode(), state);
                } catch (IllegalStateException e) {
                    log.error("[Executor] Test output parse failed — signal corruption (using fallback): {}",
                              e.getMessage());
                    testResults = TestResults.collectionErrorFallback(combined, result.getExitCode());
                }

                if (result.getExitCode() != 0) {

                    log.info("[Executor] Non-zero exit code {} — running analyzer", result.getExitCode());

                    PytestOutputAnalyzer.CollectionFailureAnalysis analysis =
                            pytestAnalyzer.analyze(rawStdout, rawStderr);

                    log.info("[Executor] Analysis: {}", analysis);

                    state.setCollectionFailureSubtype(analysis.getSubtype());
                    state.setFailingArtifact(analysis.getFailingArtifact());
                    state.setCollectionFailureReason(analysis.getReason());
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
                            return ExecutionResult.errorWithTestResults(
                                "Grounding failed: unable to cache failing artifact",
                                testResults,
                                rawTestExitCode
                            );
                        }
                    }
                }

                log.info("[Executor] Tests: {}", testResults.getSummary());

                if (rawTestExitCode != 0) {
                    log.info("[Executor] Non-zero test exit — stopping tool loop to prevent double run");
                    break;
                }
            }
        }

        return new ExecutionResult(taskDescription, toolResults, testResults, modifiedFiles, rawTestExitCode);
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

        Pattern pattern = Pattern.compile("([^:]+::[^\\s]+)\\s+(PASSED|FAILED|ERROR)");
        Matcher matcher = pattern.matcher(output);

        while (matcher.find()) {
            String name   = matcher.group(1);
            String status = matcher.group(2);
            if ("PASSED".equals(status)) passing.add(name);
            else if ("FAILED".equals(status) || "ERROR".equals(status)) failing.add(name);
        }

        String snippet = (!failing.isEmpty() || (passing.isEmpty() && failing.isEmpty()))
                ? extractErrorSnippet(output) : null;

        if (exitCode != 0 && failing.isEmpty()) {
            throw new IllegalStateException(
                "Signal corruption: exitCode=" + exitCode +
                " but no failing tests were parsed from pytest output."
            );
        }

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