package com.synapsenet.core.state;

// [Fix 4] PathNormalizer is the single source of truth for path normalization.
import com.synapsenet.core.util.PathNormalizer;

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
 *      ONLY when LLM-identified artifact is the SAME FILE as the analyzer-reported artifact.
 *      When the LLM correctly identifies a different source file (cross-file reasoning),
 *      line numbers in two different files have no mathematical relationship — the
 *      tolerance check is skipped entirely.
 *      (line check also skipped if failingArtifactLine == -1, i.e. unknown)
 *   3. rootCauseSummary non-empty
 *   4. causalExplanation non-empty
 *   5. minimalFixStrategy non-empty
 *
 * NOTE ON proposedSearchBlock:
 *   Retained for traceability and debugging — represents the LLM's perceived faulty
 *   snippet. NOT used for replace_in_file matching. The system-extracted
 *   authoritativeSearchBlock is used for all actual patching.
 *
 *   This separation cleanly splits responsibilities:
 *     authoritativeSearchBlock → system grounding (deterministic, collision-safe)
 *     proposedSearchBlock      → model reasoning  (informational, research signal)
 *
 * NOTE ON authoritativeSearchBlock:
 *   Extracted deterministically by ExecutorAgent after grounding confirms the
 *   diagnosed file is readable. Uses AUTHORITATIVE_BLOCK_RADIUS lines centered
 *   on artifactLine. Set via withAuthoritativeSearchBlock() after construction
 *   since it requires file content not available at parse time.
 *   Stored here (not transient state) so REPLAN cycles preserve it.
 *
 * All conditions 1-5 must be true for hasValidRootCauseAnalysis() to return true.
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
     * The search block the LLM perceived as faulty during REPAIR_ANALYZE.
     * Retained for traceability and debugging signal only.
     * NOT used for replace_in_file matching — see authoritativeSearchBlock.
     */
    private final String proposedSearchBlock;

    /**
     * System-extracted search block: AUTHORITATIVE_BLOCK_RADIUS lines centered
     * on artifactLine, taken verbatim from the cached file after grounding.
     *
     * This is the only block passed to replace_in_file.
     * The LLM cannot hallucinate it — it comes from the actual file.
     *
     * Null until set via withAuthoritativeSearchBlock() after grounding succeeds.
     */
    private final String authoritativeSearchBlock;

    private final boolean valid;
    private final String  invalidReason;

    // ----------------------------------------------------------------
    // Constructors (private)
    // ----------------------------------------------------------------

    /**
     * Normal constructor — runs deterministic validation.
     * cachedFileContent parameter retained for API compatibility.
     */
    private RootCauseAnalysis(
            String artifactPath,
            int    artifactLine,
            String rootCauseSummary,
            String causalExplanation,
            String minimalFixStrategy,
            String whyPreviousAttemptsFailed,
            String proposedSearchBlock,
            String authoritativeSearchBlock,
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
        this.authoritativeSearchBlock  = authoritativeSearchBlock;

        String reason = validate(
                artifactPath, artifactLine, rootCauseSummary,
                causalExplanation, minimalFixStrategy,
                knownArtifact, knownArtifactLine
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
        this.authoritativeSearchBlock  = null;
        this.valid                     = false;
        this.invalidReason             = invalidReason;
    }

    // ----------------------------------------------------------------
    // Static factories
    // ----------------------------------------------------------------

    /**
     * Build a RootCauseAnalysis from parsed field values.
     * authoritativeSearchBlock starts null — set after grounding via
     * withAuthoritativeSearchBlock().
     *
     * @param cachedFileContent  retained for call-site compatibility; unused.
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
                proposedSearchBlock,
                null,  // authoritativeSearchBlock — set after grounding
                knownArtifact, knownArtifactLine,
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

    /**
     * Return a copy of this analysis with authoritativeSearchBlock set.
     *
     * Called by ExecutorAgent after grounding confirms the diagnosed file
     * is readable and the line number is within bounds.
     *
     * Uses the full-fidelity copy constructor to preserve valid/invalidReason
     * exactly — validation is NOT re-run (it already completed at construction).
     */
    public RootCauseAnalysis withAuthoritativeSearchBlock(String block) {
        return new RootCauseAnalysis(
                this.artifactPath,
                this.artifactLine,
                this.rootCauseSummary,
                this.causalExplanation,
                this.minimalFixStrategy,
                this.whyPreviousAttemptsFailed,
                this.proposedSearchBlock,
                block,
                this.valid,
                this.invalidReason
        );
    }

    /**
     * Full-fidelity copy constructor — preserves valid/invalidReason exactly.
     * Used only by copyWithAuthoritativeBlock().
     */
    private RootCauseAnalysis(
            String  artifactPath,
            int     artifactLine,
            String  rootCauseSummary,
            String  causalExplanation,
            String  minimalFixStrategy,
            String  whyPreviousAttemptsFailed,
            String  proposedSearchBlock,
            String  authoritativeSearchBlock,
            boolean valid,
            String  invalidReason
    ) {
        this.artifactPath              = artifactPath;
        this.artifactLine              = artifactLine;
        this.rootCauseSummary          = rootCauseSummary;
        this.causalExplanation         = causalExplanation;
        this.minimalFixStrategy        = minimalFixStrategy;
        this.whyPreviousAttemptsFailed = whyPreviousAttemptsFailed;
        this.proposedSearchBlock       = proposedSearchBlock;
        this.authoritativeSearchBlock  = authoritativeSearchBlock;
        this.valid                     = valid;
        this.invalidReason             = invalidReason;
    }

    // ----------------------------------------------------------------
    // Deterministic validation
    // ----------------------------------------------------------------

    /**
     * Returns null if valid, or a human-readable failure reason string.
     */
    private static String validate(
            String artifactPath,
            int    artifactLine,
            String rootCauseSummary,
            String causalExplanation,
            String minimalFixStrategy,
            String knownArtifact,
            int    knownArtifactLine
    ) {
        // Required text fields — hard fail
        if (isBlank(rootCauseSummary))   return "rootCauseSummary is missing or empty";
        if (isBlank(causalExplanation))  return "causalExplanation is missing or empty";
        if (isBlank(minimalFixStrategy)) return "minimalFixStrategy is missing or empty";
        if (isBlank(artifactPath))       return "artifactPath is missing or empty";

        // Artifact line — hard fail ONLY when same file AND both lines known
        boolean sameFile = artifactPath != null
                && knownArtifact != null
                && normalizePath(artifactPath).equals(normalizePath(knownArtifact));

        if (sameFile && knownArtifactLine > 0 && artifactLine > 0) {
            int delta = Math.abs(artifactLine - knownArtifactLine);
            if (delta > ARTIFACT_LINE_TOLERANCE) {
                return "artifactLine " + artifactLine + " is " + delta +
                       " lines from known failure line " + knownArtifactLine +
                       " (tolerance=" + ARTIFACT_LINE_TOLERANCE + ")";
            }
        }

        return null; // valid
    }

    // ----------------------------------------------------------------
    // Path normalization — delegates to single source of truth
    // ----------------------------------------------------------------

    /**
     * [Fix 4] Delegates to PathNormalizer.normalize() — single source of truth.
     * Removed local implementation (was: replace('\\','/').replaceAll("^\\./ ","").trim()).
     * All path comparisons in the system must use one canonical normalizer.
     */
    private static String normalizePath(String path) {
        return PathNormalizer.normalize(path);
    }

    // ----------------------------------------------------------------
    // Strict search block feasibility check
    // ----------------------------------------------------------------

    /**
     * Check whether a search block exists as an exact substring of fileContent.
     * Utility for REPAIR_PATCH use — NOT called during REPAIR_ANALYZE validation.
     */
    static boolean searchBlockExistsInContent(String searchBlock, String fileContent) {
        if (isBlank(searchBlock) || isBlank(fileContent)) return false;
        if (searchBlock.trim().length() < 10) return true;
        if (fileContent.contains(searchBlock)) return true;
        String normalizedBlock   = searchBlock.replace("\r\n", "\n").replace("\r", "\n");
        String normalizedContent = fileContent.replace("\r\n", "\n").replace("\r", "\n");
        return normalizedContent.contains(normalizedBlock);
    }

    // ----------------------------------------------------------------
    // Utilities
    // ----------------------------------------------------------------

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public boolean isValid()                       { return valid; }
    public String  getInvalidReason()              { return invalidReason; }
    public String  getArtifactPath()               { return artifactPath; }
    public int     getArtifactLine()               { return artifactLine; }
    public String  getRootCauseSummary()           { return rootCauseSummary; }
    public String  getCausalExplanation()          { return causalExplanation; }
    public String  getMinimalFixStrategy()         { return minimalFixStrategy; }
    public String  getWhyPreviousAttemptsFailed()  { return whyPreviousAttemptsFailed; }
    public String  getProposedSearchBlock()        { return proposedSearchBlock; }
    public String  getAuthoritativeSearchBlock()   { return authoritativeSearchBlock; }

    public boolean hasAuthoritativeSearchBlock() {
        return !isBlank(authoritativeSearchBlock);
    }

    // ----------------------------------------------------------------
    // Prompt injection rendering
    // ----------------------------------------------------------------

    /**
     * Render for REPAIR_PATCH prompt injection.
     * Only called when isValid() == true.
     *
     * Injects authoritativeSearchBlock as a mandatory, unmodifiable search block.
     * The LLM is explicitly forbidden from altering it — only replace_block is
     * under LLM control.
     *
     * proposedSearchBlock is shown as informational context only (what the LLM
     * perceived as faulty), not as a matching candidate.
     *
     * If authoritativeSearchBlock is not yet set (should not happen in normal flow),
     * falls back to proposedSearchBlock with a warning label.
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

        if (!isBlank(authoritativeSearchBlock)) {
            sb.append("\n");
            sb.append("=== AUTHORITATIVE SEARCH BLOCK (SYSTEM-EXTRACTED — DO NOT MODIFY) ===\n");
            sb.append("This block was extracted VERBATIM from the actual file by the system.\n");
            sb.append("You MUST use it character-for-character as your search_block.\n");
            sb.append("You are NOT allowed to add, remove, or alter any character in it.\n");
            sb.append("Only your replace_block is under your control.\n");
            sb.append("```\n");
            sb.append(authoritativeSearchBlock);
            sb.append("```\n");
            sb.append("=== END AUTHORITATIVE SEARCH BLOCK ===\n");
        } else if (!isBlank(proposedSearchBlock)) {
            // Fallback — should not occur in normal flow after grounding
            sb.append("\n⚠ WARNING: No authoritative block available. ");
            sb.append("Using LLM-proposed block as fallback (may not match exactly):\n");
            sb.append("```\n");
            sb.append(proposedSearchBlock);
            sb.append("\n```\n");
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
               ", authoritativeBlock=" + (authoritativeSearchBlock != null ? "present" : "absent") +
               "}";
    }
}