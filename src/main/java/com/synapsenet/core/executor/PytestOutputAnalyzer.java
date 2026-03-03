package com.synapsenet.core.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.synapsenet.core.state.SharedState;

/**
 * PytestOutputAnalyzer - Extracts structured failure information from pytest output.
 *
 * ── Frame Ranking (NEW) ────────────────────────────────────────────────────
 *
 * All traceback frames are extracted into ScoredFrame objects, then ranked
 * deterministically using the following scoring model:
 *
 *   score =
 *     +100  if inside workspace (not venv/site-packages)
 *     -200  if third-party     (site-packages, venv, importlib, <frozen>)
 *     - 50  if test file       (test_*.py or *_test.py by filename convention)
 *     +(50 - depthFromBottom)  depth bonus (0 = deepest frame, max bonus = 50)
 *
 * Tie-breaking (in order):
 *   1. Higher score wins
 *   2. Non-test (source) over test
 *   3. Lower depthFromBottom (deeper in stack)
 *   4. Shorter path
 *   5. Earlier appearance in traceback
 *
 * Edge cases:
 *   - No workspace frames at all   → return null; fall through to ERROR/FAILED patterns
 *   - Only third-party frames      → best will have score < 0; return null; fall through
 *   - Only workspace test frames   → test frame selected (score ≥ +50)
 *   - No traceback frames at all   → assertion-only path; ASSERTION_LINE_PATTERN used
 *
 * The selected frame's lineNumber (from the stack trace) is used as failingArtifactLine
 * when the frame is a SOURCE file. For test frames, extractAssertionLine() is used as
 * a fallback (same behavior as before).
 *
 * ── Extraction Hierarchy ──────────────────────────────────────────────────
 *
 * 1. Ranked stack frame (source preferred over test, deepest preferred)
 * 2. ERROR collecting path.py
 * 3. ERROR path.py::test_name
 * 4. FAILED path.py::test_name
 * 5. Unknown
 *
 * ── Pattern Notes ─────────────────────────────────────────────────────────
 *
 * FILE_LINE_STANDARD uses [^\"\n\r]+ to prevent cross-newline captures.
 * FILE_LINE_PYTEST_SHORT matches both formats and now captures line numbers.
 * ASSERTION_LINE_PATTERN scans for path.py:N: ErrorType (fallback line extraction).
 */
@Component
public class PytestOutputAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PytestOutputAnalyzer.class);

    // ── Scoring constants ───────────────────────────────────────────────────
    private static final int SCORE_WORKSPACE   = +100;
    private static final int SCORE_THIRD_PARTY = -200;
    private static final int SCORE_TEST_FILE   =  -50;
    private static final int SCORE_DEPTH_BASE  =  +50; // +(DEPTH_BASE - depthFromBottom)

    // Frames with score below this threshold are treated as "no useful frame found"
    // and fall through to the ERROR/FAILED pattern chain.
    // Score of 0 means: workspace test frame at depthFromBottom=50 (very shallow).
    // In practice a score < 0 means all frames were third-party.
    private static final int MINIMUM_USEFUL_SCORE = 0;

    private final String workspaceRoot;

    public PytestOutputAnalyzer(
            @Value("${synapsenet.workspace.path}") String workspacePath
    ) {
        this.workspaceRoot = workspacePath.replace("\\", "/");
    }

    // ── Stack frame patterns (now capture line numbers) ─────────────────────

    // Format A: standard Python traceback
    //   File "/abs/path/src/foo.py", line 301, in load_module
    // Group 1 = path, Group 2 = line number
    // [^\"\n\r]+ explicitly excludes newlines — Java char classes match \n by default.
    private static final Pattern FILE_LINE_STANDARD =
        Pattern.compile("File \"([^\"\n\r]+\\.py)\",\\s*line (\\d+)");

    // Format B: pytest short frame
    //   src/_pytest/foo.py:301:
    //   ratelimiter/tokenbucket.py:45:
    // Group 1 = path, Group 2 = line number
    private static final Pattern FILE_LINE_PYTEST_SHORT =
        Pattern.compile("^[ \t]*([^\\s:]+\\.py):(\\d+):", Pattern.MULTILINE);

    // Fallback: assertion error line extraction (path.py:N: SomeError)
    // Takes the LAST match so early library frames are skipped.
    private static final Pattern ASSERTION_LINE_PATTERN =
        Pattern.compile(
            "^[ \t]*([^\\s:]+\\.py):(\\d+):\\s+\\w+(?:Error|Exception)",
            Pattern.MULTILINE
        );

    // Fallback patterns
    private static final Pattern ERROR_COLLECTING_PATTERN =
        Pattern.compile("ERROR collecting[ \t]+(\\S+\\.py)", Pattern.MULTILINE);

    private static final Pattern FAILED_TEST_PATTERN =
        Pattern.compile("^[ \t]*FAILED[ \t]+(\\S+?\\.py)::", Pattern.MULTILINE);

    private static final Pattern ERROR_ITEM_PATTERN =
        Pattern.compile("^[ \t]*ERROR[ \t ]+(\\S+?\\.py)::", Pattern.MULTILINE);

    // Subtype classifiers
    private static final Pattern IMPORT_ERROR_PATTERN =
        Pattern.compile("ImportError|ModuleNotFoundError");

    private static final Pattern SYNTAX_ERROR_PATTERN =
        Pattern.compile("SyntaxError|IndentationError");

    // ======================================================================
    // PUBLIC API
    // ======================================================================

    public CollectionFailureAnalysis analyze(String stdout, String stderr) {

        String combined = (stdout != null ? stdout : "") + "\n" + (stderr != null ? stderr : "");

        log.info("[Analyzer] Analyzing {} chars of pytest output", combined.length());

        if (combined.trim().isEmpty()) {
            log.warn("[Analyzer] Empty pytest output");
            return CollectionFailureAnalysis.unknown();
        }

        // ================================================================
        // PRIORITY 1: Ranked stack frame
        // ================================================================

        ScoredFrame bestFrame = selectBestFrame(combined);

        if (bestFrame != null) {
            String subtype  = determineSubtype(combined);

            // For SOURCE frames: use the stack frame's own line number directly.
            // For TEST frames:   use extractAssertionLine (same as before).
            // This is the key change — source frames now provide deterministic line numbers
            // from the stack trace rather than requiring the LLM to guess.
            int failLine;
            if (bestFrame.isSource && bestFrame.lineNumber > 0) {
                failLine = bestFrame.lineNumber;
                log.info("[Analyzer] Best frame (source=true): {} line={} [from stack trace]",
                         bestFrame.path, failLine);
            } else {
                failLine = extractAssertionLine(combined, bestFrame.path);
                log.info("[Analyzer] Best frame (source={}): {} line={}",
                         bestFrame.isSource, bestFrame.path,
                         failLine > 0 ? failLine : "unknown");
            }

            return new CollectionFailureAnalysis(
                subtype,
                bestFrame.path,
                buildFrameReason(bestFrame),
                failLine
            );
        }

        // ================================================================
        // PRIORITY 2: ERROR collecting header (fallback)
        // ================================================================

        String errorCollectingFile = extractErrorCollectingFile(combined);
        if (errorCollectingFile != null) {
            String subtype  = determineSubtype(combined);
            int    failLine = extractAssertionLine(combined, errorCollectingFile);
            log.info("[Analyzer] Extracted artifact from ERROR collecting: {}", errorCollectingFile);
            return new CollectionFailureAnalysis(
                subtype,
                errorCollectingFile,
                "Collection error in " + errorCollectingFile,
                failLine
            );
        }

        // ================================================================
        // PRIORITY 3: ERROR item line  (ERROR path.py::test_name)
        // ================================================================

        String errorItemFile = extractErrorItemFile(combined);
        if (errorItemFile != null) {
            String subtype  = determineSubtype(combined);
            int    failLine = extractAssertionLine(combined, errorItemFile);
            log.info("[Analyzer] Extracted artifact from ERROR item line: {}", errorItemFile);
            return new CollectionFailureAnalysis(
                subtype,
                errorItemFile,
                "Test item error in " + errorItemFile,
                failLine
            );
        }

        // ================================================================
        // PRIORITY 4: FAILED summary line
        // ================================================================

        String failedTestFile = extractFailedTestFile(combined);
        if (failedTestFile != null) {
            int failLine = extractAssertionLine(combined, failedTestFile);
            log.info("[Analyzer] Extracted artifact from FAILED line: {}", failedTestFile);
            return new CollectionFailureAnalysis(
                SharedState.SUBTYPE_UNKNOWN,
                failedTestFile,
                "Test file contains failing assertion",
                failLine
            );
        }

        if (combined.contains("ERROR: not found") ||
            combined.contains("no tests ran") ||
            combined.contains("no tests collected")) {
            return new CollectionFailureAnalysis(
                SharedState.SUBTYPE_NO_TESTS_FOUND, null, "No tests found or collected", -1);
        }

        log.warn("[Analyzer] Could not extract artifact from output");
        return CollectionFailureAnalysis.unknown();
    }

    // ======================================================================
    // FRAME COLLECTION AND SCORING
    // ======================================================================

    /**
     * Collect all traceback frames from both formats, score them, and return
     * the highest-scoring frame that meets the minimum useful score threshold.
     *
     * Returns null if:
     *   - No frames found at all (assertion-only failure)
     *   - Best frame score < MINIMUM_USEFUL_SCORE (only third-party frames present)
     */
    private ScoredFrame selectBestFrame(String output) {

        List<ScoredFrame> frames = collectFrames(output);

        if (frames.isEmpty()) {
            log.info("[Analyzer] No traceback frames found — falling through to ERROR/FAILED patterns");
            return null;
        }

        // Assign depthFromBottom: last frame in list = depth 0 (deepest call)
        int total = frames.size();
        for (int i = 0; i < total; i++) {
            frames.get(i).depthFromBottom = total - 1 - i;
        }

        // Score all frames and log for debugging
        for (ScoredFrame f : frames) {
            f.computeScore();
            log.debug("[Analyzer] Frame: score={} path={} line={} source={} workspace={} "
                      + "thirdParty={} depthFromBottom={}",
                      f.score, f.path, f.lineNumber, f.isSource,
                      f.isWorkspace, f.isThirdParty, f.depthFromBottom);
        }

        // Sort: primary = score desc, then tie-break rules
        frames.sort(
            Comparator
                .comparingInt((ScoredFrame f) -> f.score).reversed()
                .thenComparing(f -> f.isSource ? 0 : 1)          // source before test
                .thenComparingInt(f -> f.depthFromBottom)         // lower depth (deeper) wins
                .thenComparingInt(f -> f.path.length())           // shorter path wins
                .thenComparingInt(f -> f.appearanceIndex)         // earlier in traceback wins
        );

        ScoredFrame best = frames.get(0);

        // If best is below threshold, all frames are third-party — fall through
        if (best.score < MINIMUM_USEFUL_SCORE) {
            log.warn("[Analyzer] Best frame score={} is below threshold={} (only third-party frames). "
                     + "Falling through to ERROR/FAILED patterns.", best.score, MINIMUM_USEFUL_SCORE);
            return null;
        }

        log.info("[Analyzer] Selected frame: path={} line={} score={} source={} "
                 + "workspace={} depthFromBottom={} (from {} candidates)",
                 best.path, best.lineNumber, best.score,
                 best.isSource, best.isWorkspace, best.depthFromBottom, total);

        return best;
    }

    /**
     * Collect all traceback frames from both Format A and Format B patterns.
     * Deduplicates by (normalizedPath, lineNumber) key.
     * Preserves appearance order for tie-breaking.
     *
     * Both formats are scanned over the full combined output.
     * Format A results are added first (they appear first in standard tracebacks),
     * then Format B to catch pytest's condensed frame lines.
     */
    private List<ScoredFrame> collectFrames(String output) {

        List<ScoredFrame> frames  = new ArrayList<>();
        Set<String>       seenKeys = new LinkedHashSet<>();

        // Format A: File "/abs/path/foo.py", line N
        Matcher stdMatcher = FILE_LINE_STANDARD.matcher(output);
        while (stdMatcher.find()) {
            String rawPath = stdMatcher.group(1);
            int    lineNo  = parseLineNumber(stdMatcher.group(2));
            addFrame(rawPath, lineNo, false, frames, seenKeys);
        }

        // Format B: foo.py:301:
        Matcher shortMatcher = FILE_LINE_PYTEST_SHORT.matcher(output);
        while (shortMatcher.find()) {
            String rawPath = shortMatcher.group(1).trim();
            int    lineNo  = parseLineNumber(shortMatcher.group(2));
            addFrame(rawPath, lineNo, true, frames, seenKeys);
        }

        return frames;
    }

    /**
     * Normalize a raw path from a stack frame, classify it, and add it to
     * the frame list if it passes sanity checks and has not been seen before.
     *
     * @param rawPath      raw path string as it appears in the traceback
     * @param lineNo       line number from the frame (> 0)
     * @param isRelative   true for Format B (already relative), false for Format A (absolute)
     * @param frames       accumulator list
     * @param seenKeys     deduplication set
     */
    private void addFrame(String rawPath, int lineNo, boolean isRelative,
                          List<ScoredFrame> frames, Set<String> seenKeys) {

        if (!isSingleLine(rawPath)) return;

        boolean thirdParty = isNonProjectFrame(rawPath);

        String path;
        if (isRelative) {
            // Format B is already relative; trust it as-is after venv/site-packages check
            path = rawPath;
        } else {
            // Format A: convert absolute path to workspace-relative
            if (thirdParty) {
                // Keep the raw path for third-party frames (used for scoring, not caching)
                path = rawPath;
            } else {
                path = extractRelativePath(rawPath);
                if (path == null) return;
            }
        }

        if (!isSingleLine(path)) return;

        String key = path + ":" + lineNo;
        if (seenKeys.contains(key)) return;
        seenKeys.add(key);

        boolean isSource    = !isTestFile(path);
        boolean isWorkspace = !thirdParty;

        frames.add(new ScoredFrame(
            path, lineNo, isSource, isWorkspace, thirdParty, frames.size()
        ));
    }

    // ======================================================================
    // SCORING MODEL
    // ======================================================================

    /**
     * Internal scored frame — holds all metadata needed for the ranking model.
     *
     *   score =
     *     +100  workspace
     *     -200  third-party
     *     - 50  test file
     *     +(50 - depthFromBottom)   [clamped at 0 if depth > 50]
     *
     * depthFromBottom is assigned by selectBestFrame() after the full list is built.
     */
    private static class ScoredFrame {

        final String  path;
        final int     lineNumber;       // line from stack frame; -1 if unknown
        final boolean isSource;         // true if NOT a test file
        final boolean isWorkspace;      // true if inside project (not venv/site-packages)
        final boolean isThirdParty;     // true if venv, site-packages, etc.
        final int     appearanceIndex;  // insertion order (earlier = smaller)

        int depthFromBottom;  // 0 = deepest frame; set after full collection
        int score;            // computed by computeScore()

        ScoredFrame(String path, int lineNumber, boolean isSource,
                    boolean isWorkspace, boolean isThirdParty, int appearanceIndex) {
            this.path           = path;
            this.lineNumber     = lineNumber;
            this.isSource       = isSource;
            this.isWorkspace    = isWorkspace;
            this.isThirdParty   = isThirdParty;
            this.appearanceIndex = appearanceIndex;
        }

        void computeScore() {
            score = 0;
            if (isWorkspace)  score += SCORE_WORKSPACE;
            if (isThirdParty) score += SCORE_THIRD_PARTY;
            if (!isSource)    score += SCORE_TEST_FILE;
            score += Math.max(0, SCORE_DEPTH_BASE - depthFromBottom);
        }

        @Override
        public String toString() {
            return String.format("ScoredFrame{path=%s, line=%d, score=%d, source=%b, "
                                 + "workspace=%b, depth=%d}",
                                 path, lineNumber, score, isSource, isWorkspace, depthFromBottom);
        }
    }

    // ======================================================================
    // HELPERS
    // ======================================================================

    /**
     * Build a human-readable reason string for the selected frame.
     * Used in CollectionFailureAnalysis.reason for observability.
     */
    private String buildFrameReason(ScoredFrame frame) {
        String kind = frame.isSource ? "source" : "test";
        return String.format("Stack trace %s frame: %s (score=%d, depthFromBottom=%d)",
                             kind, frame.path, frame.score, frame.depthFromBottom);
    }

    /**
     * Extract the failing line number for a known artifact path from the pytest output.
     * Used as fallback when the stack frame line number is unavailable or when
     * the selected frame is a test file.
     *
     * Scans for:  path.py:N: SomeError
     * Takes the LAST match whose path suffix matches the artifact.
     *
     * Returns -1 if no match found.
     */
    private int extractAssertionLine(String output, String artifactPath) {
        if (artifactPath == null || output == null) return -1;

        String filename = artifactPath.contains("/")
            ? artifactPath.substring(artifactPath.lastIndexOf('/') + 1)
            : artifactPath;

        int lastLine = -1;
        Matcher m = ASSERTION_LINE_PATTERN.matcher(output);
        while (m.find()) {
            String matchedPath = m.group(1).trim();
            if (matchedPath.endsWith(filename) || matchedPath.equals(artifactPath)) {
                int parsed = parseLineNumber(m.group(2));
                if (parsed > 0) lastLine = parsed;
            }
        }

        if (lastLine > 0) {
            log.info("[Analyzer] Extracted assertion line {} from {}", lastLine, artifactPath);
        }
        return lastLine;
    }

    /**
     * Returns true if the file is a test file by naming convention.
     * Layout-agnostic: uses filename only, no directory prefix assumptions.
     */
    private boolean isTestFile(String path) {
        String filename = path.contains("/")
            ? path.substring(path.lastIndexOf('/') + 1)
            : path;
        return filename.startsWith("test_") || filename.endsWith("_test.py");
    }

    /**
     * Returns true if the path belongs to a virtual environment, site-packages,
     * or other non-project frame. Handles both /venv/ and /.venv/ (with leading dot).
     */
    private boolean isNonProjectFrame(String path) {
        String norm = path.replace("\\", "/");
        return norm.contains("/venv/")        ||
               norm.contains("/.venv/")       ||
               norm.startsWith(".venv/")      ||
               norm.startsWith("venv/")       ||
               norm.contains("site-packages") ||
               norm.contains("<frozen")       ||
               norm.contains("/importlib/");
    }

    /**
     * Final sanity check: a valid file path must be a single line with no
     * newlines, no ">" markers, no internal spaces, and must end with .py.
     */
    private boolean isSingleLine(String path) {
        if (path == null)                       return false;
        if (path.contains("\n"))                return false;
        if (path.contains("\r"))                return false;
        if (path.contains(">"))                 return false;
        if (!path.endsWith(".py"))              return false;
        return true;
    }

    /**
     * Convert an absolute path from a stack trace to a workspace-relative path.
     *
     * Strategy (in order):
     * 1. Strip workspace root prefix if present.
     * 2. If already relative (no leading slash / Windows drive), return as-is.
     * 3. Last-2-components fallback.
     *
     * Returns null only if input is null or empty.
     */
    private String extractRelativePath(String absolutePath) {
        if (absolutePath == null) return null;
        String norm = absolutePath.replace("\\", "/").trim();
        if (norm.isEmpty()) return null;

        if (workspaceRoot != null && !workspaceRoot.isEmpty()) {
            String wsNorm = workspaceRoot.replace("\\", "/");
            if (!wsNorm.endsWith("/")) wsNorm += "/";
            if (norm.startsWith(wsNorm)) {
                String relative = norm.substring(wsNorm.length());
                if (!relative.isEmpty()) return relative;
            }
        }

        if (!norm.startsWith("/") && !norm.matches("[A-Za-z]:/.*")) {
            return norm;
        }

        String[] parts = norm.split("/");
        if (parts.length >= 2) return parts[parts.length - 2] + "/" + parts[parts.length - 1];
        return parts.length > 0 ? parts[parts.length - 1] : null;
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

    private String extractErrorItemFile(String output) {
        Matcher m = ERROR_ITEM_PATTERN.matcher(output);
        if (m.find()) {
            String testFile = m.group(1);
            log.info("[Analyzer] Found ERROR item file: {}", testFile);
            return testFile;
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

    private int parseLineNumber(String s) {
        if (s == null) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ======================================================================
    // PUBLIC RESULT TYPE
    // ======================================================================

    /**
     * Result of analyzing a pytest output block.
     * Unchanged from original — no structural modifications required.
     */
    public static class CollectionFailureAnalysis {

        private final String subtype;
        private final String failingArtifact;
        private final String reason;
        private final int    failingLine;

        public CollectionFailureAnalysis(String subtype, String failingArtifact,
                                         String reason, int failingLine) {
            this.subtype         = subtype;
            this.failingArtifact = failingArtifact;
            this.reason          = reason;
            this.failingLine     = failingLine;
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
            return String.format(
                "CollectionFailureAnalysis{subtype=%s, artifact=%s, line=%d, reason=%s}",
                subtype, failingArtifact, failingLine, reason);
        }
    }
}