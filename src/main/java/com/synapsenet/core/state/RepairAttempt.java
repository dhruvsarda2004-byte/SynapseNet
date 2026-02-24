package com.synapsenet.core.state;

/**
 * Immutable record of a single repair cycle (REPAIR_ANALYZE → REPAIR_PATCH → VALIDATE → REPLAN).
 *
 * Populated by SimpleTaskOrchestrator.captureRepairAttempt() at the moment REPLAN fires,
 * BEFORE softReset() clears per-cycle state.
 *
 * GUARD: Only recorded when REPLAN fires from REPAIR_ANALYZE or REPAIR_PATCH.
 * REPRODUCE-phase REPLANs do not produce a RepairAttempt — they contain no
 * repair signal and would pollute history with meaningless NO_PATCH entries.
 *
 * Stored in SharedState.repairHistory (capped at 5 entries).
 * Injected into PlannerAgent.buildReplanPrompt() as structured history.
 */
public final class RepairAttempt {

    public enum Outcome {
        /** REPAIR_ANALYZE: analysis JSON was malformed or failed structural validation. */
        ANALYSIS_INVALID,
        /** REPAIR_ANALYZE: retry cap hit before valid analysis was produced. */
        ANALYSIS_CAP_EXCEEDED,
        /** REPAIR_PATCH: replace_in_file search block did not match anything in the file. */
        SEARCH_FAILED,
        /** REPAIR_PATCH: replace_in_file search block matched multiple locations. */
        SEARCH_AMBIGUOUS,
        /** REPAIR_PATCH: patch applied but tests still failed in VALIDATE. */
        VALIDATE_FAILED,
        /** REPAIR_PATCH: patch introduced a syntax error detected in VALIDATE. */
        SYNTAX_ERROR,
        /** REPAIR_PATCH: retry cap exhausted without any patch being applied. */
        NO_PATCH
    }

    private final int     attemptNumber;
    private final Outcome outcome;

    /** Short description of what was patched, e.g. "replace_in_file on rewrite.py". */
    private final String patchSummary;

    /** The exact search_block the LLM used — null if no patch was attempted. */
    private final String searchBlockUsed;

    /** From RootCauseAnalysis.getRootCauseSummary() — what the LLM diagnosed. */
    private final String rootCauseSummary;

    /** From RootCauseAnalysis.getMinimalFixStrategy() — what fix the LLM planned. */
    private final String minimalFixStrategy;

    /** Failure subtype from subsequent test run (IMPORT_ERROR, SYNTAX_ERROR, etc.) */
    private final String validationFailureSubtype;

    /** Line number of new failure after patch, -1 if unknown. */
    private final int    validationFailureLine;

    /** Raw failure reason from PytestOutputAnalyzer. */
    private final String validationFailureReason;

    private RepairAttempt(Builder b) {
        this.attemptNumber            = b.attemptNumber;
        this.outcome                  = b.outcome;
        this.patchSummary             = b.patchSummary;
        this.searchBlockUsed          = b.searchBlockUsed;
        this.rootCauseSummary         = b.rootCauseSummary;
        this.minimalFixStrategy       = b.minimalFixStrategy;
        this.validationFailureSubtype = b.validationFailureSubtype;
        this.validationFailureLine    = b.validationFailureLine;
        this.validationFailureReason  = b.validationFailureReason;
    }

    // ----------------------------------------------------------------
    // Prompt rendering
    // ----------------------------------------------------------------

    /**
     * Render as plain-text block for Planner REPLAN prompt injection.
     * Plain text (not JSON) to avoid nested-JSON confusion in weak local models.
     */
    public String toPromptSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("Attempt #").append(attemptNumber).append("\n");
        sb.append("  Outcome     : ").append(outcome).append("\n");

        // Diagnosis context — only present when REPAIR_ANALYZE succeeded
        if (rootCauseSummary != null && !rootCauseSummary.isBlank()) {
            sb.append("  Diagnosis   : ").append(rootCauseSummary).append("\n");
        }
        if (minimalFixStrategy != null && !minimalFixStrategy.isBlank()) {
            sb.append("  Fix planned : ").append(minimalFixStrategy).append("\n");
        }
        if (patchSummary != null && !patchSummary.isBlank()) {
            sb.append("  Patch       : ").append(patchSummary).append("\n");
        }

        switch (outcome) {
            case ANALYSIS_INVALID:
                sb.append("  Failure     : Analysis JSON failed structural validation.\n");
                sb.append("  Hint        : Produce all required fields with exact artifact path.\n");
                break;

            case ANALYSIS_CAP_EXCEEDED:
                sb.append("  Failure     : Retry cap hit before valid analysis produced.\n");
                sb.append("  Hint        : Consider a different diagnostic approach.\n");
                break;

            case SEARCH_FAILED:
                sb.append("  Failure     : Search block did not match any text in the file.\n");
                sb.append("  Hint        : Copy search_block EXACTLY from the file window.\n");
                sb.append("                No line-number prefixes. No '>>' marker.\n");
                if (searchBlockUsed != null && !searchBlockUsed.isBlank()) {
                    sb.append("  Bad block   : |\n");
                    for (String line : firstNLines(searchBlockUsed, 3).split("\n")) {
                        sb.append("                ").append(line).append("\n");
                    }
                }
                break;

            case SEARCH_AMBIGUOUS:
                sb.append("  Failure     : Search block matched multiple locations.\n");
                sb.append("  Hint        : Include at least 5 lines of unique surrounding context.\n");
                break;

            case VALIDATE_FAILED:
                sb.append("  Failure     : Patch applied but tests still fail.\n");
                if (validationFailureSubtype != null)
                    sb.append("  New subtype : ").append(validationFailureSubtype).append("\n");
                if (validationFailureLine > 0)
                    sb.append("  New line    : ").append(validationFailureLine).append("\n");
                if (validationFailureReason != null && !validationFailureReason.isBlank())
                    sb.append("  Reason      : ").append(validationFailureReason).append("\n");
                sb.append("  Hint        : The diagnosis or fix strategy needs revisiting.\n");
                break;

            case SYNTAX_ERROR:
                sb.append("  Failure     : Patch introduced a syntax error.\n");
                if (validationFailureLine > 0)
                    sb.append("  Syntax line : ").append(validationFailureLine).append("\n");
                sb.append("  Hint        : replace_block had invalid Python. Check indentation.\n");
                break;

            case NO_PATCH:
                sb.append("  Failure     : No patch was applied (retry cap exhausted).\n");
                sb.append("  Hint        : Try a simpler, more targeted fix.\n");
                break;
        }

        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Accessors
    // ----------------------------------------------------------------

    public int     getAttemptNumber()            { return attemptNumber; }
    public Outcome getOutcome()                  { return outcome; }
    public String  getPatchSummary()             { return patchSummary; }
    public String  getSearchBlockUsed()          { return searchBlockUsed; }
    public String  getRootCauseSummary()         { return rootCauseSummary; }
    public String  getMinimalFixStrategy()       { return minimalFixStrategy; }
    public String  getValidationFailureSubtype() { return validationFailureSubtype; }
    public int     getValidationFailureLine()    { return validationFailureLine; }
    public String  getValidationFailureReason()  { return validationFailureReason; }

    // ----------------------------------------------------------------
    // Builder
    // ----------------------------------------------------------------

    public static Builder builder(int attemptNumber, Outcome outcome) {
        return new Builder(attemptNumber, outcome);
    }

    public static final class Builder {
        private final int     attemptNumber;
        private final Outcome outcome;
        private String patchSummary             = null;
        private String searchBlockUsed          = null;
        private String rootCauseSummary         = null;
        private String minimalFixStrategy       = null;
        private String validationFailureSubtype = null;
        private int    validationFailureLine    = -1;
        private String validationFailureReason  = null;

        private Builder(int attemptNumber, Outcome outcome) {
            this.attemptNumber = attemptNumber;
            this.outcome       = outcome;
        }

        public Builder patchSummary(String v)             { this.patchSummary = v;             return this; }
        public Builder searchBlockUsed(String v)          { this.searchBlockUsed = v;          return this; }
        public Builder rootCauseSummary(String v)         { this.rootCauseSummary = v;         return this; }
        public Builder minimalFixStrategy(String v)       { this.minimalFixStrategy = v;       return this; }
        public Builder validationFailureSubtype(String v) { this.validationFailureSubtype = v; return this; }
        public Builder validationFailureLine(int v)       { this.validationFailureLine = v;    return this; }
        public Builder validationFailureReason(String v)  { this.validationFailureReason = v;  return this; }

        public RepairAttempt build() { return new RepairAttempt(this); }
    }

    // ----------------------------------------------------------------
    // Utilities
    // ----------------------------------------------------------------

    private static String firstNLines(String text, int n) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            if (i > 0) sb.append("\n");
            sb.append(lines[i]);
        }
        if (lines.length > n) sb.append("\n  [+" + (lines.length - n) + " more lines]");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "RepairAttempt{#" + attemptNumber + ", outcome=" + outcome + "}";
    }
}