package com.synapsenet.core.executor;

/**
 * Shared canonicalization utility for search-block matching.
 *
 * This is the SINGLE normalization entry-point used by both
 * RealToolExecutor (executeReplaceInFile) and RootCauseAnalysis
 * (proposedSearchBlock feasibility check).
 *
 * Every comparison goes through identical preprocessing — no ad-hoc
 * normalization variants elsewhere.
 */
public final class SearchBlockMatcher {

    private SearchBlockMatcher() {}

    /**
     * Canonicalize {@code text} for reliable substring matching.
     *
     * Steps (applied in order):
     * <ol>
     *   <li>Normalize line endings to {@code \n} — plain {@code String.replace},
     *       not regex, to avoid the double-escape CRLF bug.</li>
     *   <li>Strip line-number prefixes injected by
     *       {@code ExecutorAgent.extractFileWindow}: {@code "  301 | "} or
     *       {@code ">> "}</li>
     *   <li>Remove {@code SharedState} truncation markers
     *       ({@code # <<< TRUNCATED:…>>>})</li>
     *   <li>Trim trailing whitespace per line</li>
     *   <li>Collapse runs of spaces/tabs inside lines to a single space</li>
     *   <li>Remove blank lines</li>
     *   <li>Trim the whole result</li>
     * </ol>
     *
     * @param text raw text (may be {@code null})
     * @return canonicalized text; never {@code null}
     */
    public static String canonicalize(String text) {
        if (text == null) return "";

        // 1. Normalize line endings — plain replace, no regex (avoids CRLF bug)
        text = text.replace("\r\n", "\n").replace("\r", "\n");

        // 2. Strip line-number prefixes: "  301 | " or ">> "
        text = text.replaceAll("(?m)^\\s*\\d+\\s*\\|\\s*", "");
        text = text.replaceAll("(?m)^>>\\s*", "");

        // 3. Remove truncation markers
        text = text.replaceAll("(?m)^# <<< TRUNCATED:.*>>>\\s*$", "");

        // 4. Trim trailing whitespace per line
        text = text.replaceAll("(?m)[ \\t]+$", "");

        // 5. Collapse runs of spaces/tabs inside lines to a single space
        text = text.replaceAll("[ \\t]+", " ");

        // 6. Remove blank lines
        text = text.replaceAll("(?m)^\\s*\\n", "");

        return text.trim();
    }
}