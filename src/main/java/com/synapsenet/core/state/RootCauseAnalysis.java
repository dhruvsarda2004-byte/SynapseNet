package com.synapsenet.core.state;

import org.slf4j.LoggerFactory;

/**
 * Typed result of REPAIR_ANALYZE phase.
 *
 * Populated by ExecutorAgent after parsing the LLM's raw JSON response.
 * Validated structurally before being stored in SharedState.
 * Injected into REPAIR_PATCH prompt by ExecutorAgent.
 * Injected into REPLAN prompt by PlannerAgent (with failure annotation).
 *
 * VALIDATION RULES (deterministic — no numeric confidence):
 *   1. artifactPath non-empty AND matches state.getFailingArtifact()
 *   2. artifactLine > 0 AND within ±ARTIFACT_LINE_TOLERANCE of state.getFailingArtifactLine()
 *      (line check skipped if failingArtifactLine == -1, i.e. unknown)
 *   3. rootCauseSummary non-empty
 *   4. causalExplanation non-empty
 *   5. minimalFixStrategy non-empty
 *   6. proposedSearchBlock present AND approximately found in cached file content
 *      (normalized whitespace comparison — not naive substring)
 *
 * All conditions must be true for hasValidRootCauseAnalysis() to return true.
 */
public final class RootCauseAnalysis {

    /** Lines of tolerance when checking LLM-reported line vs known failure line. */
    public static final int ARTIFACT_LINE_TOLERANCE = 50;

    /** Required JSON field names — used in Executor prompt and parsing. */
    public static final String FIELD_ARTIFACT_PATH        = "artifactPath";
    public static final String FIELD_ARTIFACT_LINE        = "artifactLine";
    public static final String FIELD_ROOT_CAUSE_SUMMARY   = "rootCauseSummary";
    public static final String FIELD_CAUSAL_EXPLANATION   = "causalExplanation";
    public static final String FIELD_MINIMAL_FIX          = "minimalFixStrategy";
    public static final String FIELD_PREV_ATTEMPTS_EVAL   = "whyPreviousAttemptsFailed";
    public static final String FIELD_PROPOSED_SEARCH_BLOCK = "proposedSearchBlock";

    // ----------------------------------------------------------------
    // Fields
    // ----------------------------------------------------------------

    private final String artifactPath;
    private final int    artifactLine;
    private final String rootCauseSummary;
    private final String causalExplanation;
    private final String minimalFixStrategy;
    private final String whyPreviousAttemptsFailed;

    /**
     * The exact search_block the LLM intends to use in REPAIR_PATCH.
     * Validated against cached file content (normalized) before advancing.
     * Optional — if null, feasibility check is skipped (no false rejection).
     */
    private final String proposedSearchBlock;

    private final boolean valid;
    private final String  invalidReason;

    // ----------------------------------------------------------------
    // Constructors (private)
    // ----------------------------------------------------------------

    /**
     * Normal constructor — runs deterministic validation.
     * fileContent is the concatenated content of all cached files, used for
     * proposedSearchBlock feasibility check. May be null (check skipped).
     */
    private RootCauseAnalysis(
            String artifactPath,
            int    artifactLine,
            String rootCauseSummary,
            String causalExplanation,
            String minimalFixStrategy,
            String whyPreviousAttemptsFailed,
            String proposedSearchBlock,
            String knownArtifact,
            int    knownArtifactLine,
            String cachedFileContent
    ) {
        this.artifactPath              = artifactPath;
        this.artifactLine              = artifactLine;
        this.rootCauseSummary          = rootCauseSummary;
        this.causalExplanation         = causalExplanation;
        this.minimalFixStrategy        = minimalFixStrategy;
        this.whyPreviousAttemptsFailed = whyPreviousAttemptsFailed;
        this.proposedSearchBlock       = proposedSearchBlock;

        String reason = validate(
                artifactPath, artifactLine, rootCauseSummary,
                causalExplanation, minimalFixStrategy,
                proposedSearchBlock, knownArtifact, knownArtifactLine,
                cachedFileContent
        );
        this.valid         = (reason == null);
        this.invalidReason = reason;
    }

    /**
     * Sentinel constructor — skips validation, sets valid/invalidReason directly.
     * Used only by {@link #invalid(String)} when JSON parsing fails entirely.
     */
    private RootCauseAnalysis(String invalidReason, @SuppressWarnings("unused") boolean sentinel) {
        this.artifactPath              = null;
        this.artifactLine              = 0;
        this.rootCauseSummary          = null;
        this.causalExplanation         = null;
        this.minimalFixStrategy        = null;
        this.whyPreviousAttemptsFailed = null;
        this.proposedSearchBlock       = null;
        this.valid                     = false;
        this.invalidReason             = invalidReason;
    }

    // ----------------------------------------------------------------
    // Static factories
    // ----------------------------------------------------------------

    /**
     * Build a RootCauseAnalysis from parsed field values.
     *
     * @param cachedFileContent  concatenated content of all files in state.getRecentFileReads()
     *                           used for proposedSearchBlock feasibility check.
     *                           Pass null to skip the check.
     */
    public static RootCauseAnalysis of(
            String artifactPath,
            int    artifactLine,
            String rootCauseSummary,
            String causalExplanation,
            String minimalFixStrategy,
            String whyPreviousAttemptsFailed,
            String proposedSearchBlock,
            String knownArtifact,
            int    knownArtifactLine,
            String cachedFileContent
    ) {
        return new RootCauseAnalysis(
                artifactPath, artifactLine, rootCauseSummary,
                causalExplanation, minimalFixStrategy, whyPreviousAttemptsFailed,
                proposedSearchBlock, knownArtifact, knownArtifactLine,
                cachedFileContent
        );
    }

    /**
     * Build an invalid sentinel when JSON parsing fails entirely.
     * Uses the sentinel constructor — no validation runs, valid is forced false.
     */
    public static RootCauseAnalysis invalid(String reason) {
        return new RootCauseAnalysis(reason, false);
    }

    // ----------------------------------------------------------------
    // Deterministic validation
    // ----------------------------------------------------------------

    /**
     * Returns null if valid, or a human-readable failure reason string.
     *
     * Fix A: Artifact path check demoted from hard-reject to soft (never blocks).
     * PytestOutputAnalyzer heuristic picks the deepest non-test frame, which is
     * where the failure manifests — not necessarily the root cause file.
     * The LLM reads the raw exception and may correctly identify an upstream
     * causal file. Hard-failing on path mismatch caused an unresolvable loop.
     * Mismatch is logged in ExecutorAgent.parseAnalysis() for observability.
     *
     * Hard failures (enforced):
     *   - Required text fields missing/empty
     *   - artifactLine out of tolerance (when both known)
     *   - proposedSearchBlock not found in cached file content
     *
     * Soft check (never blocks):
     *   - artifactPath mismatch with knownArtifact
     */
    private static String validate(
            String artifactPath,
            int    artifactLine,
            String rootCauseSummary,
            String causalExplanation,
            String minimalFixStrategy,
            String proposedSearchBlock,
            String knownArtifact,
            int    knownArtifactLine,
            String cachedFileContent
    ) {
        // Required text fields — hard fail
        if (isBlank(rootCauseSummary))   return "rootCauseSummary is missing or empty";
        if (isBlank(causalExplanation))  return "causalExplanation is missing or empty";
        if (isBlank(minimalFixStrategy)) return "minimalFixStrategy is missing or empty";
        if (isBlank(artifactPath))       return "artifactPath is missing or empty";

        // Artifact path: SOFT CHECK — never blocks (see Javadoc above).
        // Mismatch logged by caller (ExecutorAgent.parseAnalysis) for observability.

        // Artifact line — hard fail only when both known and LLM provided one.
        //
        // TOLERANCE STRATEGY:
        // We compute an effective file length from three sources, taking the maximum:
        //   1. cachedFileContent line count — partial window, often truncated to 500 lines
        //   2. knownArtifactLine * 4 — if the failure is at line 300, file is at least 1200 lines
        //   3. artifactLine * 4 — same reasoning for the LLM-reported line
        // This prevents tolerance collapse when only a partial window is cached.
        //
        // Tolerance = 20% of effective file length, floored at 75 lines.
        if (knownArtifactLine > 0 && artifactLine > 0) {
            int cachedLines = (cachedFileContent != null && !cachedFileContent.isEmpty())
                    ? cachedFileContent.split("\n", -1).length
                    : 0;
            int effectiveFileLength = Math.max(cachedLines,
                                      Math.max(knownArtifactLine * 4,
                                               artifactLine * 4));
            int dynamicTolerance = Math.max(75, (int)(effectiveFileLength * 0.20));
            int delta = Math.abs(artifactLine - knownArtifactLine);
            
            // ===== DIAGNOSTIC LOGGING (PART 4) =====
            // VALIDATION: Log structured metadata about tolerance check
            LoggerFactory.getLogger(RootCauseAnalysis.class).info(
                    "[VALIDATION] knownLine={}, proposedLine={}, delta={}, tolerance={}",
                    knownArtifactLine,
                    artifactLine,
                    delta,
                    dynamicTolerance);
            
            if (delta > dynamicTolerance) {
                return "artifactLine " + artifactLine + " is " + delta +
                       " lines from known failure line " + knownArtifactLine +
                       " (tolerance=" + dynamicTolerance +
                       ", effectiveFileLength=" + effectiveFileLength + ")";
            }
        }

        // proposedSearchBlock feasibility — hard fail only when both present
        if (!isBlank(proposedSearchBlock) && !isBlank(cachedFileContent)) {
            if (!searchBlockExistsInContent(proposedSearchBlock, cachedFileContent)) {
                return "proposedSearchBlock does not approximately match any content in cached files. " +
                       "LLM must copy search_block from the file excerpt shown, not reconstruct from memory.";
            }
        }

        return null; // valid
    }

    // ----------------------------------------------------------------
    // Normalized search block feasibility check
    // ----------------------------------------------------------------

    /**
     * Check whether proposedSearchBlock approximately exists in fileContent.
     *
     * WHY NOT naive contains():
     *   - Windowed injection may alter leading whitespace
     *   - LLM may omit trailing spaces or use different line endings
     *   - Minor indentation differences cause false rejections
     *
     * STRATEGY — normalize both sides then check:
     *   1. Collapse all runs of whitespace within each line to a single space
     *   2. Strip leading/trailing whitespace from each line
     *   3. Remove blank lines
     *   4. Join with \n and search for normalized block as substring of normalized content
     *
     * This is deterministic and tolerant of formatting differences while still
     * catching hallucinated blocks that share no real code with the file.
     *
     * Minimum block length enforced: blocks under 10 normalized chars are skipped
     * (too short to be meaningful — would match anywhere).
     */
    static boolean searchBlockExistsInContent(String searchBlock, String fileContent) {
        String normBlock   = normalizeForSearch(searchBlock);
        String normContent = normalizeForSearch(fileContent);

        if (normBlock.length() < 10) {
            // Too short to validate meaningfully — pass through
            return true;
        }

        return normContent.contains(normBlock);
    }

    /**
     * Normalize text for approximate search comparison.
     * - Unify line endings to \n
     * - Strip line-number prefixes ("  301 | " or ">> " added by extractFileWindow)
     * - Trim each line
     * - Collapse internal whitespace runs to single space
     * - Remove blank lines
     * - Join with \n
     */
    private static String normalizeForSearch(String text) {
        if (text == null) return "";

        // Unify line endings
        String unified = text.replace("\r\n", "\n").replace("\r", "\n");

        StringBuilder sb = new StringBuilder();
        for (String line : unified.split("\n", -1)) {
            // Strip extractFileWindow line-number prefix: "  301 | " or "   >> "
            String stripped = line.replaceAll("^\\s*\\d+\\s*\\|\\s?", "")
                                  .replaceAll("^\\s*>>\\s?", "");
            // Strip SharedState truncation marker — must not appear in search content
            // Marker format: "# <<< TRUNCATED: N lines omitted >>>"
            if (stripped.trim().startsWith("# <<< TRUNCATED:")) continue;
            // Trim and collapse internal whitespace
            String trimmed = stripped.trim().replaceAll("\\s+", " ");
            if (!trimmed.isEmpty()) {
                sb.append(trimmed).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ----------------------------------------------------------------
    // Utilities
    // ----------------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String normalize(String path) {
        if (path == null) return "";
        return path.replace('\\', '/').replaceAll("^\\./", "").trim();
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public boolean isValid()                     { return valid; }
    public String  getInvalidReason()            { return invalidReason; }
    public String  getArtifactPath()             { return artifactPath; }
    public int     getArtifactLine()             { return artifactLine; }
    public String  getRootCauseSummary()         { return rootCauseSummary; }
    public String  getCausalExplanation()        { return causalExplanation; }
    public String  getMinimalFixStrategy()       { return minimalFixStrategy; }
    public String  getWhyPreviousAttemptsFailed(){ return whyPreviousAttemptsFailed; }
    public String  getProposedSearchBlock()      { return proposedSearchBlock; }

    // ----------------------------------------------------------------
    // Prompt injection rendering
    // ----------------------------------------------------------------

    /**
     * Render for REPAIR_PATCH prompt injection.
     * Only called when isValid() == true.
     */
    public String toRepairPatchPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ROOT CAUSE ANALYSIS ===\n");
        sb.append("Artifact    : ").append(artifactPath);
        if (artifactLine > 0) sb.append(" (line ").append(artifactLine).append(")");
        sb.append("\n");
        sb.append("Root cause  : ").append(rootCauseSummary).append("\n");
        sb.append("Explanation : ").append(causalExplanation).append("\n");
        sb.append("Fix strategy: ").append(minimalFixStrategy).append("\n");
        if (!isBlank(proposedSearchBlock)) {
            sb.append("Search block: use the block you identified in analysis as starting point.\n");
        }
        sb.append("=== END ROOT CAUSE ANALYSIS ===");
        return sb.toString();
    }

    /**
     * Render for REPLAN history injection.
     */
    public String toReplanPromptBlock(String patchOutcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("Previous root cause analysis (outcome: ").append(patchOutcome).append("):\n");
        sb.append("  Artifact   : ").append(artifactPath);
        if (artifactLine > 0) sb.append(" (line ").append(artifactLine).append(")");
        sb.append("\n");
        sb.append("  Root cause : ").append(rootCauseSummary).append("\n");
        sb.append("  Fix tried  : ").append(minimalFixStrategy).append("\n");
        sb.append("⚠ This analysis led to a failed patch. Re-evaluate assumptions.\n");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "RootCauseAnalysis{valid=" + valid +
               ", artifact=" + artifactPath + ":" + artifactLine +
               ", cause=" + (rootCauseSummary != null ?
                   rootCauseSummary.substring(0, Math.min(60, rootCauseSummary.length())) : "null") +
               ", searchBlock=" + (proposedSearchBlock != null ? "present" : "absent") +
               "}";
    }
}