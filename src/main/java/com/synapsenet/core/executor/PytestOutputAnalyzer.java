package com.synapsenet.core.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.synapsenet.core.state.SharedState;

/**
 * PytestOutputAnalyzer - Extracts structured failure information from pytest output.
 *
 * Extraction hierarchy (in order of precedence):
 *
 * 1. Deepest workspace stack frame — two formats:
 *    a) Standard Python:  File "/abs/path/src/foo.py", line N
 *    b) pytest short:     src/_pytest/foo.py:301:
 *    Source files (src/) preferred over test files (testing/, tests/).
 *
 * 2. ERROR collecting path.py  — fallback only.
 *
 * 3. FAILED path.py::test_name  — pure assertion failure.
 *
 * 4. Unknown.
 *
 * KEY FIX: FILE_LINE_STANDARD uses [^\"\n\r]+ (not [^\"]+) to prevent
 * capturing across newlines. Java character classes match \n by default,
 * causing pytest source-marker lines (> ...) to bleed into path captures.
 */
@Component
public class PytestOutputAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PytestOutputAnalyzer.class);

    // Format A: standard Python traceback
    //   File "/abs/path/src/_pytest/assertion/rewrite.py", line 301, in load_module
    //
    // ✅ CRITICAL: [^\"\n\r]+ explicitly excludes newlines.
    //    Java's [^\"] matches \n by default (unlike . which doesn't).
    //    Without \n\r exclusion, the pattern captures across the blank line
    //    between pytest's "> source" line and the "file.py:N:" line,
    //    producing a corrupted multiline artifact.
    private static final Pattern FILE_LINE_STANDARD =
        Pattern.compile("File \"([^\"\n\r]+\\.py)\",\\s*line (\\d+)");

    // Format B: pytest short frame — appears after the "> source" marker line
    //   src/_pytest/assertion/rewrite.py:301:
    // Anchored at line start, only known project directory prefixes.
    private static final Pattern FILE_LINE_PYTEST_SHORT =
        Pattern.compile("^[ \t]*((?:src|testing|tests)/[^\s:]+\\.py):\\d+:", Pattern.MULTILINE);

    // Fallback patterns
    private static final Pattern ERROR_COLLECTING_PATTERN =
        Pattern.compile("ERROR collecting[ \t]+(\\S+\\.py)", Pattern.MULTILINE);

    private static final Pattern FAILED_TEST_PATTERN =
        Pattern.compile("^[ \t]*FAILED[ \t]+(\\S+?\\.py)::", Pattern.MULTILINE);

    // Subtype classifiers
    private static final Pattern IMPORT_ERROR_PATTERN =
        Pattern.compile("ImportError|ModuleNotFoundError");

    private static final Pattern SYNTAX_ERROR_PATTERN =
        Pattern.compile("SyntaxError|IndentationError");

    public CollectionFailureAnalysis analyze(String stdout, String stderr) {

        String combined = (stdout != null ? stdout : "") + "\n" + (stderr != null ? stderr : "");

        log.info("[Analyzer] Analyzing {} chars of pytest output", combined.length());

        if (combined.trim().isEmpty()) {
            log.warn("[Analyzer] Empty pytest output");
            return CollectionFailureAnalysis.unknown();
        }

        // ================================================================
        // PRIORITY 1: Deepest workspace stack frame
        // ================================================================

        FrameCandidate bestFrame = extractBestFrameFromTrace(combined);

        if (bestFrame != null) {
            String subtype = determineSubtype(combined);
            log.info("[Analyzer] Best frame (source={}, line={}): {}",
                    bestFrame.isSource, bestFrame.failingLine, bestFrame.path);
            return new CollectionFailureAnalysis(
                subtype,
                bestFrame.path,
                "Stack trace points to " + bestFrame.path,
                bestFrame.failingLine
            );
        }

        // ================================================================
        // PRIORITY 2: ERROR collecting header (fallback)
        // ================================================================

        String errorCollectingFile = extractErrorCollectingFile(combined);

        if (errorCollectingFile != null) {
            String subtype = determineSubtype(combined);
            log.info("[Analyzer] Extracted artifact from ERROR collecting: {}", errorCollectingFile);
            return new CollectionFailureAnalysis(
                subtype,
                errorCollectingFile,
                "Collection error in " + errorCollectingFile
            );
        }

        // ================================================================
        // PRIORITY 3: FAILED summary line
        // ================================================================

        String failedTestFile = extractFailedTestFile(combined);

        if (failedTestFile != null) {
            log.info("[Analyzer] Extracted artifact from FAILED line: {}", failedTestFile);
            return new CollectionFailureAnalysis(
                SharedState.SUBTYPE_UNKNOWN,
                failedTestFile,
                "Test file contains failing assertion"
            );
        }

        if (combined.contains("ERROR: not found") ||
            combined.contains("no tests ran") ||
            combined.contains("no tests collected")) {
            return new CollectionFailureAnalysis(
                SharedState.SUBTYPE_NO_TESTS_FOUND, null, "No tests found or collected");
        }

        log.warn("[Analyzer] Could not extract artifact from output");
        return CollectionFailureAnalysis.unknown();
    }

    /**
     * Scan both frame formats. Within each category (source/test), take the
     * last valid occurrence. Prefer source frames over test frames.
     */
    private FrameCandidate extractBestFrameFromTrace(String output) {

        FrameCandidate lastSourceFrame = null;
        FrameCandidate lastTestFrame   = null;

        // --- Format A: File "/abs/path/foo.py", line N ---
        // Group 1 = path, Group 2 = line number
        Matcher stdMatcher = FILE_LINE_STANDARD.matcher(output);
        while (stdMatcher.find()) {
            String fullPath = stdMatcher.group(1);
            if (isNonProjectFrame(fullPath)) continue;

            String relative = extractRelativePath(fullPath);
            if (relative == null || !isSingleLine(relative)) continue;

            int lineNum = -1;
            try { lineNum = Integer.parseInt(stdMatcher.group(2)); } catch (NumberFormatException ignored) {}

            FrameCandidate c = categorize(relative, lineNum);
            if (c.isSource) lastSourceFrame = c;
            else            lastTestFrame   = c;
        }

        // --- Format B: src/_pytest/foo.py:301: ---
        // Group 1 = relative path, line number follows first colon after path
        Matcher shortMatcher = FILE_LINE_PYTEST_SHORT.matcher(output);
        while (shortMatcher.find()) {
            String relative = shortMatcher.group(1).trim();
            if (isNonProjectFrame(relative) || !isSingleLine(relative)) continue;

            // Extract line number from the full match: "  src/foo.py:301:"
            int lineNum = -1;
            String fullMatch = shortMatcher.group(0);
            Matcher colonNum = Pattern.compile(":(\\d+):").matcher(fullMatch);
            if (colonNum.find()) {
                try { lineNum = Integer.parseInt(colonNum.group(1)); } catch (NumberFormatException ignored) {}
            }

            FrameCandidate c = categorize(relative, lineNum);
            if (c.isSource) lastSourceFrame = c;
            else            lastTestFrame   = c;
        }

        if (lastSourceFrame != null) return lastSourceFrame;
        return lastTestFrame;
    }

    /**
     * Final sanity check: a valid file path is a single line with no
     * newlines, no ">" markers, no internal spaces, and ends with .py.
     */
    private boolean isSingleLine(String path) {
        if (path == null) return false;
        if (path.contains("\n") || path.contains("\r")) return false;
        if (path.contains(">"))                          return false;
        if (!path.endsWith(".py"))                       return false;
        return true;
    }

    private boolean isNonProjectFrame(String path) {
        return path.contains("/venv/")         ||
               path.contains("site-packages")  ||
               path.contains("<frozen")        ||
               path.contains("/importlib/");
    }

    private FrameCandidate categorize(String relative, int failingLine) {
        boolean isTest = relative.startsWith("testing/") || relative.startsWith("tests/");
        return new FrameCandidate(relative, !isTest, failingLine);
    }

    private String extractErrorCollectingFile(String output) {
        Matcher m = ERROR_COLLECTING_PATTERN.matcher(output);
        if (m.find()) {
            String path = m.group(1).trim();
            log.info("[Analyzer] Found ERROR collecting: {}", path);
            return path;
        }
        return null;
    }

    private String extractFailedTestFile(String output) {
        Matcher m = FAILED_TEST_PATTERN.matcher(output);
        if (m.find()) {
            String testFile = m.group(1);
            log.info("[Analyzer] Found FAILED test file: {}", testFile);
            return testFile;
        }
        return null;
    }

    private String determineSubtype(String output) {
        if (IMPORT_ERROR_PATTERN.matcher(output).find()) return SharedState.SUBTYPE_IMPORT_ERROR;
        if (SYNTAX_ERROR_PATTERN.matcher(output).find()) return SharedState.SUBTYPE_SYNTAX_ERROR;
        if (output.contains("no tests ran") || output.contains("no tests collected"))
            return SharedState.SUBTYPE_NO_TESTS_FOUND;
        return SharedState.SUBTYPE_UNKNOWN;
    }

    /**
     * Convert absolute path to workspace-relative using directory anchors.
     * Returns null if path cannot be made relative.
     */
    private String extractRelativePath(String absolutePath) {
        if (absolutePath == null) return null;
        absolutePath = absolutePath.replace("\\", "/").trim();

        int idx;
        idx = absolutePath.indexOf("/src/");
        if (idx != -1) return absolutePath.substring(idx + 1);

        idx = absolutePath.indexOf("/testing/");
        if (idx != -1) return absolutePath.substring(idx + 1);

        idx = absolutePath.indexOf("/tests/");
        if (idx != -1) return absolutePath.substring(idx + 1);

        // Already relative
        if (absolutePath.startsWith("src/") ||
            absolutePath.startsWith("testing/") ||
            absolutePath.startsWith("tests/")) {
            return absolutePath;
        }

        String[] parts = absolutePath.split("/");
        if (parts.length >= 2) return parts[parts.length - 2] + "/" + parts[parts.length - 1];
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    private static class FrameCandidate {
        final String  path;
        final boolean isSource;
        final int     failingLine;  // -1 if not available
        FrameCandidate(String path, boolean isSource, int failingLine) {
            this.path        = path;
            this.isSource    = isSource;
            this.failingLine = failingLine;
        }
    }

    public static class CollectionFailureAnalysis {

        private final String subtype;
        private final String failingArtifact;
        private final String reason;
        private final int    failingLine;   // -1 if not extractable from output

        public CollectionFailureAnalysis(String subtype, String failingArtifact,
                                         String reason, int failingLine) {
            this.subtype         = subtype;
            this.failingArtifact = failingArtifact;
            this.reason          = reason;
            this.failingLine     = failingLine;
        }

        /** Convenience constructor for cases where line number is unavailable. */
        public CollectionFailureAnalysis(String subtype, String failingArtifact, String reason) {
            this(subtype, failingArtifact, reason, -1);
        }

        public static CollectionFailureAnalysis unknown() {
            return new CollectionFailureAnalysis(
                SharedState.SUBTYPE_UNKNOWN, null, "Could not determine failure cause", -1);
        }

        public String getSubtype()         { return subtype; }
        public String getFailingArtifact() { return failingArtifact; }
        public String getReason()          { return reason; }
        public int    getFailingLine()     { return failingLine; }

        @Override
        public String toString() {
            return String.format("CollectionFailureAnalysis{subtype=%s, artifact=%s, line=%d, reason=%s}",
                    subtype, failingArtifact, failingLine, reason);
        }
    }
}