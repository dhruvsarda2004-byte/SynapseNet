package com.synapsenet.core.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsenet.core.agent.ExecutorAgent;
import com.synapsenet.core.filesystem.FileSystemManager;
import com.synapsenet.core.filesystem.FileSystemManager.SearchResult;
import com.synapsenet.core.state.SharedState;
import com.synapsenet.core.util.PathNormalizer;

import java.util.ArrayList;
import java.util.List;

/**
 * RealToolExecutor - HARDENED Production-grade tool execution
 *
 * Features:
 * - Defensive JSON validation on all tool arguments
 * - Never returns null ToolResult
 * - Clear error messages with exitCode=1 on failures
 * - Integrates with FileSystemManager and PythonExecutor
 *
 * Changes vs prior version:
 *   [FIX-1] normalizeWhitespace: CRLF handled with plain String.replace,
 *           not double-escaped regex literals that silently never matched.
 *   [FIX-2] executeReplaceInFile: fuzzy sliding-window fallback removed.
 *           After exact match fails, delegates to SearchBlockMatcher.canonicalize
 *           for a simple, deterministic contains() check. No sliding window.
 *   [FIX-3] executeReplaceInFile: canonical match now APPLIES the replacement
 *           instead of returning not-found. findCanonicalSpans() returns exact
 *           [start,end) char offsets. Single span → splice. Multiple spans →
 *           AMBIGUOUS_MATCH error. Zero spans → NOT_FOUND error.
 */
@Component
@Profile("ollama")
public class RealToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(RealToolExecutor.class);

    private final FileSystemManager fileSystem;
    private final PythonExecutor pythonExecutor;
    private final ObjectMapper jsonMapper;

    public RealToolExecutor(FileSystemManager fileSystem,
                            PythonExecutor pythonExecutor) {
        this.fileSystem = fileSystem;
        this.pythonExecutor = pythonExecutor;
        this.jsonMapper = new ObjectMapper();
    }

    @Override
    public ToolResult execute(ExecutorAgent.ToolCall call) {
        return execute(call, null);
    }

    @Override
    public ToolResult execute(ExecutorAgent.ToolCall call, SharedState sharedState) {
        String tool = call.getTool();

        log.info("[ToolExecutor] Executing tool: {}", tool);

        try {
            ToolResult result = switch (tool) {
                case "read_file"      -> executeReadFile(call.getArgs(), sharedState);
                case "read_lines"     -> executeReadLines(call.getArgs());
                case "write_file"     -> executeWriteFile(call.getArgs(), sharedState);
                case "replace_in_file"-> executeReplaceInFile(call.getArgs(), sharedState);
                case "grep"           -> executeGrep(call.getArgs());
                case "list_files"     -> executeListFiles(call.getArgs(), sharedState);
                case "file_tree"      -> executeFileTree(call.getArgs(), sharedState);
                case "run_tests"      -> executeRunTests(call.getArgs());
                case "run_python"     -> executeRunPython(call.getArgs());
                default -> new ToolResult(tool, 1, "", "Unknown tool: " + tool, null, null);
            };

            // ✅ DEFENSIVE: Ensure result is never null
            if (result == null) {
                log.error("[ToolExecutor] Tool '{}' returned null result", tool);
                return new ToolResult(tool, 1, "", "Tool returned null result", null, null);
            }

            return result;

        } catch (Exception e) {
            log.error("[ToolExecutor] Tool execution failed: {}", e.getMessage(), e);
            return new ToolResult(tool, 1, "", "Tool execution error: " + e.getMessage(), null, null);
        }
    }

    /* ============================================================
       GENERIC ARG VALIDATION UTILITIES
       ============================================================ */

    /** Safely extract required text field from JSON. */
    private String requireText(JsonNode json, String field, String toolName) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return node.asText();
    }

    /** Safely extract optional text field with default value. */
    private String optionalText(JsonNode json, String field, String defaultValue) {
        if (!json.has(field)) return defaultValue;
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) return defaultValue;
        return node.asText();
    }

    /** Safely extract optional integer field with default value. */
    private int optionalInt(JsonNode json, String field, int defaultValue) {
        if (!json.has(field)) return defaultValue;
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) return defaultValue;
        return node.asInt();
    }

    /* ============================================================
       TOOL IMPLEMENTATIONS
       ============================================================ */

    /** Read entire file. Args: {"path": "src/main.py"} */
    private ToolResult executeReadFile(String args, SharedState sharedState) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            String path = PathNormalizer.normalize(requireText(json, "path", "read_file"));
            String content = fileSystem.readFile(path);

            if (sharedState != null) {
                sharedState.cacheFileRead(path, content);
            }

            return new ToolResult("read_file", 0, content, "", path, null);

        } catch (IllegalArgumentException e) {
            return new ToolResult("read_file", 1, "", e.getMessage(), null, null);
        } catch (FileSystemManager.FileSystemException e) {
            return new ToolResult("read_file", 1, "", "Failed to read file: " + e.getMessage(), null, null);
        } catch (Exception e) {
            return new ToolResult("read_file", 1, "", "JSON parsing error: " + e.getMessage(), null, null);
        }
    }

    /** Read specific line range. Args: {"path": "src/main.py", "start": 10, "end": 20} */
    private ToolResult executeReadLines(String args) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            String path  = PathNormalizer.normalize(requireText(json, "path", "read_lines"));
            int    start = optionalInt(json, "start", 1);
            int    end   = optionalInt(json, "end", -1);

            List<String> lines = fileSystem.readLines(path, start, end);
            String content = String.join("\n", lines);

            return new ToolResult("read_lines", 0, content, "", path, null);

        } catch (IllegalArgumentException e) {
            return new ToolResult("read_lines", 1, "", e.getMessage(), null, null);
        } catch (FileSystemManager.FileSystemException e) {
            return new ToolResult("read_lines", 1, "", "Failed to read lines: " + e.getMessage(), null, null);
        } catch (Exception e) {
            return new ToolResult("read_lines", 1, "", "JSON parsing error: " + e.getMessage(), null, null);
        }
    }

    /** Write content to file. Args: {"path": "src/main.py", "content": "..."} */
    private ToolResult executeWriteFile(String args, SharedState state) {
        try {
            JsonNode json    = jsonMapper.readTree(args);
            String   path    = PathNormalizer.normalize(requireText(json, "path", "write_file"));
            String   content = requireText(json, "content", "write_file");

            fileSystem.writeFile(path, content);

            // [Fix 2] Re-cache file content immediately after write so prompt builders
            // and RootCauseAnalysis see current content, not stale pre-patch content.
            if (state != null) {
                try {
                    String newContent = fileSystem.readFile(path);
                    state.cacheFileRead(path, newContent);
                    log.info("[ToolExecutor] write_file: cache updated for '{}'", path);
                } catch (FileSystemManager.FileSystemException e) {
                    log.warn("[ToolExecutor] write_file: cache update failed for '{}': {}", path, e.getMessage());
                }
            }

            return new ToolResult("write_file", 0, "File written successfully", "", path, null);

        } catch (IllegalArgumentException e) {
            return new ToolResult("write_file", 1, "", e.getMessage(), null, null);
        } catch (FileSystemManager.FileSystemException e) {
            return new ToolResult("write_file", 1, "", "Failed to write file: " + e.getMessage(), null, null);
        } catch (Exception e) {
            return new ToolResult("write_file", 1, "", "JSON parsing error: " + e.getMessage(), null, null);
        }
    }

    /**
     * Replace a multi-line block in a file.
     *
     * Match strategy (in order):
     *   1. Exact substring match — no normalization. Ambiguity-checked.
     *      Splices via substring() — never String.replace().
     *   2. Canonical line-by-line match — strips leading whitespace per line.
     *      findCanonicalSpans() returns all [start,end) spans in LF-normalized content.
     *        - Exactly ONE span  → re-indent replaceBlock to match, splice.
     *        - More than one span → AMBIGUOUS_MATCH error (no write).
     *        - Zero spans         → NOT_FOUND error.
     *
     * [Fix 7] CRLF preservation: original line endings detected before normalization.
     *         If original file uses CRLF, final content is converted back before write.
     *         This prevents line-ending drift in files committed with CRLF.
     *
     * [Fix 2] Cache update: after a successful write, re-read and re-cache via SharedState
     *         so prompt builders see current file content, not stale pre-patch content.
     */
    private ToolResult executeReplaceInFile(String args, SharedState state) {
        try {
            JsonNode json = jsonMapper.readTree(args);

            String path         = PathNormalizer.normalize(requireText(json, "path", "replace_in_file"));
            String searchBlock  = stripMarkdownFences(requireText(json, "search_block",  "replace_in_file")); // [Step6]
            String replaceBlock = stripMarkdownFences(requireText(json, "replace_block", "replace_in_file")); // [Step6]

            log.info("[ToolExecutor] replace_in_file: searching in {}", path);
            log.debug("[ToolExecutor] Search block:\n{}", searchBlock);

            String originalContent = fileSystem.readFile(path);

            // [Fix 7] Detect line ending style BEFORE any normalization.
            boolean usesCRLF = originalContent.contains("\r\n");
            if (usesCRLF) {
                log.info("[ToolExecutor] replace_in_file: CRLF detected in '{}' — will preserve after write", path);
            }

            // ── Stage 1: Exact substring match ──────────────────────────────
            int firstIndex = originalContent.indexOf(searchBlock);

            if (firstIndex != -1) {
                int secondIndex = originalContent.indexOf(searchBlock, firstIndex + 1);
                if (secondIndex != -1) {
                    return new ToolResult(
                        "replace_in_file", 1, "",
                        "AMBIGUOUS_MATCH: Search block found at multiple locations. " +
                        "Provide more surrounding context lines to disambiguate.",
                        path, null
                    );
                }
                // Single exact match — splice, never String.replace()
                String updated =
                    originalContent.substring(0, firstIndex) +
                    replaceBlock +
                    originalContent.substring(firstIndex + searchBlock.length());

                // [Fix 7] Re-apply CRLF if original file used it
                if (usesCRLF) {
                    updated = updated.replace("\r\n", "\n").replace("\n", "\r\n");
                }

                fileSystem.writeFile(path, updated);
                log.info("[ToolExecutor] Successfully replaced block in {} (exact match)", path);

                // [Fix 2] Re-cache after successful write
                recacheAfterWrite(path, state);

                return new ToolResult("replace_in_file", 0, "Block replaced successfully", "", path, null);
            }

            // ── Stage 2: Canonical line-by-line match ────────────────────────
            log.info("[ToolExecutor] Exact match failed — trying canonical match...");
            String normContent = originalContent.replace("\r\n", "\n").replace("\r", "\n");
            List<int[]> spans  = findCanonicalSpans(normContent, searchBlock);

            if (spans.isEmpty()) {
                log.warn("[ToolExecutor] Could not find search block in {}", path);
                String fileSnippet = getRelevantSnippet(originalContent, searchBlock);
                String errorMessage = String.format(
                    "Search block not found in file.\n\n" +
                    "You searched for:\n%s\n\n" +
                    "Actual file contains:\n%s\n\n" +
                    "Tip: Copy the EXACT text from the file, including whitespace.",
                    truncate(searchBlock, 200),
                    fileSnippet
                );
                return new ToolResult("replace_in_file", 1, "", errorMessage, path, null);
            }

            if (spans.size() > 1) {
                log.warn("[ToolExecutor] AMBIGUOUS_MATCH: {} canonical spans found in {}", spans.size(), path);
                return new ToolResult(
                    "replace_in_file", 1, "",
                    "AMBIGUOUS_MATCH: Search block (after whitespace normalization) matches " +
                    spans.size() + " locations in the file. " +
                    "Provide more surrounding context lines to disambiguate.",
                    path, null
                );
            }

            // Exactly one canonical span — apply via splice + re-indent
            int[]  span      = spans.get(0);
            int    spanStart = span[0];
            int    spanEnd   = span[1];
            String origSpan  = normContent.substring(spanStart, spanEnd);

            log.info("[ToolExecutor] Canonical match found at span [{}, {}] in {}", spanStart, spanEnd, path);

            String reindented = reindentToMatch(replaceBlock, origSpan);
            String updated =
                normContent.substring(0, spanStart) +
                reindented +
                normContent.substring(spanEnd);

            // [Fix 7] Re-apply CRLF if original file used it
            if (usesCRLF) {
                updated = updated.replace("\r\n", "\n").replace("\n", "\r\n");
            }

            fileSystem.writeFile(path, updated);
            log.info("[ToolExecutor] Successfully replaced block in {} (canonical match, span [{},{}])",
                     path, spanStart, spanEnd);

            // [Fix 2] Re-cache after successful write
            recacheAfterWrite(path, state);

            return new ToolResult("replace_in_file", 0, "Block replaced successfully", "", path, null);

        } catch (IllegalArgumentException e) {
            return new ToolResult("replace_in_file", 1, "", e.getMessage(), null, null);
        } catch (FileSystemManager.FileSystemException e) {
            return new ToolResult("replace_in_file", 1, "", "Failed to replace block: " + e.getMessage(), null, null);
        } catch (Exception e) {
            log.error("[ToolExecutor] Unexpected error in replace_in_file", e);
            return new ToolResult("replace_in_file", 1, "", "Failed to replace block: " + e.getMessage(), null, null);
        }
    }

    /**
     * [Fix 2] Re-read file from disk and update SharedState cache after a successful write.
     * Only called on write success — never on failure paths.
     */
    private void recacheAfterWrite(String path, SharedState state) {
        if (state == null) return;
        try {
            String newContent = fileSystem.readFile(path);
            state.cacheFileRead(path, newContent);
            log.info("[ToolExecutor] Cache updated for '{}' after write", path);
        } catch (FileSystemManager.FileSystemException e) {
            log.warn("[ToolExecutor] Cache update failed for '{}' after write: {}", path, e.getMessage());
        }
    }

    /**
     * Find all character spans [start, end) in normalizedContent where searchBlock
     * matches line-by-line after stripping leading whitespace from each line.
     *
     * @param normalizedContent file content with \r\n already converted to \n
     * @param searchBlock       the block the LLM provided (may have different indentation)
     * @return list of int[]{charStart, charEnd} — empty=not found, size>1=ambiguous
     *
     * Algorithm:
     *   1. Split both into lines.
     *   2. Strip trailing blank lines from search pattern (LLM often appends them).
     *   3. Precompute lineStart[i] = char offset of line i in normalizedContent.
     *   4. Sliding window: compare trimmed lines for strict equality.
     *   5. Convert matched window to [charStart, charEnd) using lineStart[].
     *      charEnd is capped at normalizedContent.length() to guard against
     *      off-by-one when the match is at end-of-file without a trailing newline.
     */
    private List<int[]> findCanonicalSpans(String normalizedContent, String searchBlock) {
        String normSearch = searchBlock.replace("\r\n", "\n").replace("\r", "\n");

        String[] contentLines = normalizedContent.split("\n", -1);
        String[] rawSearch    = normSearch.split("\n", -1);

        // Drop trailing blank lines from the search pattern
        int searchLen = rawSearch.length;
        while (searchLen > 0 && rawSearch[searchLen - 1].trim().isEmpty()) {
            searchLen--;
        }
        if (searchLen == 0 || contentLines.length < searchLen) return List.of();

        // Trimmed canonical form of each search line
        String[] canonSearch = new String[searchLen];
        for (int i = 0; i < searchLen; i++) {
            canonSearch[i] = rawSearch[i].trim();
        }

        // Precompute char offset of each line's first character.
        // lineStart[i+1] = lineStart[i] + len(contentLines[i]) + 1  (the '\n').
        int[] lineStart = new int[contentLines.length + 1];
        lineStart[0] = 0;
        for (int i = 0; i < contentLines.length; i++) {
            lineStart[i + 1] = lineStart[i] + contentLines[i].length() + 1;
        }

        List<int[]> spans = new ArrayList<>();

        outer:
        for (int i = 0; i <= contentLines.length - searchLen; i++) {
            for (int j = 0; j < searchLen; j++) {
                if (!contentLines[i + j].trim().equals(canonSearch[j])) {
                    continue outer;
                }
            }
            int charStart = lineStart[i];
            int charEnd   = Math.min(lineStart[i + searchLen], normalizedContent.length());
            spans.add(new int[]{charStart, charEnd});
        }

        return spans;
    }

    /**
     * Adjust the indentation of replaceBlock so its baseline matches origSpan.
     *
     * "Baseline" = leading whitespace of the first non-empty line.
     *
     * For each line in replaceBlock:
     *   - Blank line                          → preserved verbatim
     *   - Starts with replaceBlock's baseline → swap prefix for origSpan's baseline
     *   - Deeper indentation / no match       → left as-is
     *
     * Only the shared baseline prefix is swapped — deterministic, no guessing.
     */
    private String reindentToMatch(String replaceBlock, String origSpan) {
        String origIndent    = detectBaseIndent(origSpan);
        String replaceIndent = detectBaseIndent(replaceBlock);

        if (origIndent.equals(replaceIndent)) return replaceBlock;

        String   norm  = replaceBlock.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = norm.split("\n", -1);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                sb.append(line);
            } else if (!replaceIndent.isEmpty() && line.startsWith(replaceIndent)) {
                sb.append(origIndent).append(line.substring(replaceIndent.length()));
            } else {
                sb.append(line);
            }
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Return the leading whitespace of the first non-empty line in block.
     * Returns "" if no non-empty line is found.
     */
    private String detectBaseIndent(String block) {
        for (String line : block.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1)) {
            if (!line.trim().isEmpty()) {
                int i = 0;
                while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
                return line.substring(0, i);
            }
        }
        return "";
    }

    /**
     * Strip markdown code fence wrappers from LLM-generated search/replace blocks.
     *
     * The LLM operates in markdown space and routinely wraps code in fences:
     *   ```python        or    ```    or    ``
     *   ...content...
     *   ```
     *
     * The matcher operates in raw text space. Fences are not present in source
     * files, so a fenced search block will never match, causing REPLAN spirals.
     *
     * Strips ONLY the wrapping fence lines:
     *   - Leading line: ``` or `` optionally followed by a language hint
     *   - Trailing line: ``` or `` exactly (after trimming)
     *
     * Internal backticks are NEVER modified.
     * Applied to both searchBlock and replaceBlock before any matching logic. [Step6]
     */
    private String stripMarkdownFences(String text) {
        if (text == null) return text;
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0) return text;

        int start = 0;
        int end   = lines.length;

        // Strip leading fence line: ``` or `` optionally followed by a language word
        String firstTrimmed = lines[0].trim();
        if (firstTrimmed.startsWith("```") || firstTrimmed.startsWith("``")) {
            String afterFence = firstTrimmed.startsWith("```")
                ? firstTrimmed.substring(3)
                : firstTrimmed.substring(2);
            if (afterFence.isEmpty() || afterFence.matches("[a-zA-Z0-9_+\\-]+")) {
                start = 1;
                log.debug("[ToolExecutor] stripMarkdownFences: stripped leading fence");
            }
        }

        // Strip trailing fence line: exactly ``` or ``
        if (end > start) {
            String lastTrimmed = lines[end - 1].trim();
            if (lastTrimmed.equals("```") || lastTrimmed.equals("``")) {
                end = end - 1;
                log.debug("[ToolExecutor] stripMarkdownFences: stripped trailing fence");
            }
        }

        if (start == 0 && end == lines.length) return text; // nothing stripped

        String[] kept = java.util.Arrays.copyOfRange(lines, start, end);
        String result = String.join("\n", kept);
        log.info("[ToolExecutor] stripMarkdownFences: stripped fence ({} -> {} lines)",
                 lines.length, kept.length);
        return result;
    }

    /**
     * Normalize whitespace for diagnostic/snippet purposes only.
     *
     * [FIX-1] CRLF handling uses plain {@code String.replace}, not the previous
     * double-escaped regex literals ({@code "\\\\r\\\\n"}) which silently never
     * matched real carriage-return characters.
     *
     * NOTE: This method is intentionally NOT used by the canonical match path.
     * All match logic goes through {@link SearchBlockMatcher#canonicalize}.
     */
    private String normalizeWhitespace(String text) {
        return text
            .replace("\r\n", "\n")   // [FIX-1] was: .replaceAll("\\\\r\\\\n", "\n")
            .replace("\r", "\n")     // [FIX-1] was: .replaceAll("\\\\r", "\n")
            .replaceAll("[ \\t]+", " ")
            .replaceAll(" *\n *", "\n")
            .trim();
    }

    private String getRelevantSnippet(String content, String searchBlock) {
        String[] searchLines = searchBlock.split("\n");
        String firstLine = "";
        for (String line : searchLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) { firstLine = trimmed; break; }
        }

        if (firstLine.isEmpty()) {
            return content.substring(0, Math.min(300, content.length()));
        }

        String[] contentLines = content.split("\n");
        int foundAt = -1;
        for (int i = 0; i < contentLines.length; i++) {
            if (contentLines[i].trim().contains(firstLine) ||
                firstLine.contains(contentLines[i].trim())) {
                foundAt = i;
                break;
            }
        }

        if (foundAt == -1) {
            return content.substring(0, Math.min(300, content.length()));
        }

        int start = Math.max(0, foundAt - 2);
        int end   = Math.min(contentLines.length, foundAt + 5);

        StringBuilder snippet = new StringBuilder();
        for (int i = start; i < end; i++) snippet.append(contentLines[i]).append("\n");
        return snippet.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    /** Search for pattern in files. Args: {"pattern": "def.*calculate", "path": "src/"} */
    private ToolResult executeGrep(String args) {
        try {
            JsonNode json    = jsonMapper.readTree(args);
            String   pattern = requireText(json, "pattern", "grep");
            String   path    = PathNormalizer.normalize(requireText(json, "path", "grep"));

            List<SearchResult> results = fileSystem.grep(pattern, path);
            StringBuilder output = new StringBuilder();
            for (SearchResult result : results) output.append(result.toString()).append("\n");

            return new ToolResult("grep", 0, output.toString(), "", null, null);

        } catch (IllegalArgumentException e) {
            return new ToolResult("grep", 1, "", e.getMessage(), null, null);
        } catch (FileSystemManager.FileSystemException e) {
            return new ToolResult("grep", 1, "", "Failed to search: " + e.getMessage(), null, null);
        } catch (Exception e) {
            return new ToolResult("grep", 1, "", "JSON parsing error: " + e.getMessage(), null, null);
        }
    }

    /** List files in directory. Args: {"path": "src/"} or {} */
    private ToolResult executeListFiles(String args, SharedState state) {
        try {
            JsonNode     json  = jsonMapper.readTree(args);
            String       path  = PathNormalizer.normalize(optionalText(json, "path", "."));
            List<String> files = fileSystem.listFiles(path);
            String       output = String.join("\n", files);

            if (state != null) {
                state.setStructureDiscovered(true);
                // [Fix 5] Populate authoritative workspace file index from deterministic walk.
                // No ASCII tree parsing — uses FileSystemManager.getAllFilePaths() directly.
                try {
                    state.setWorkspaceFiles(fileSystem.getAllFilePaths());
                } catch (FileSystemManager.FileSystemException e) {
                    log.warn("[ToolExecutor] list_files: workspace index update failed: {}", e.getMessage());
                }
            }

            return new ToolResult("list_files", 0, output, "", null, null);

        } catch (FileSystemManager.FileSystemException e) {
            return new ToolResult("list_files", 1, "", "Failed to list files: " + e.getMessage(), null, null);
        } catch (Exception e) {
            return new ToolResult("list_files", 1, "", "JSON parsing error: " + e.getMessage(), null, null);
        }
    }

    /** Show directory tree. Args: {"path": "src/", "depth": 3} */
    private ToolResult executeFileTree(String args, SharedState state) {
        try {
            JsonNode json  = jsonMapper.readTree(args);
            String   path  = PathNormalizer.normalize(optionalText(json, "path", "."));
            int      depth = optionalInt(json, "depth", 3);
            String   tree  = fileSystem.getFileTree(path, depth);

            if (state != null) {
                state.setStructureDiscovered(true);
                // [Fix 5] Populate authoritative workspace file index from deterministic walk.
                // No ASCII tree parsing — uses FileSystemManager.getAllFilePaths() directly.
                try {
                    state.setWorkspaceFiles(fileSystem.getAllFilePaths());
                } catch (FileSystemManager.FileSystemException e) {
                    log.warn("[ToolExecutor] file_tree: workspace index update failed: {}", e.getMessage());
                }
            }

            return new ToolResult("file_tree", 0, tree, "", null, null);

        } catch (FileSystemManager.FileSystemException e) {
            return new ToolResult("file_tree", 1, "", "Failed to build tree: " + e.getMessage(), null, null);
        } catch (Exception e) {
            return new ToolResult("file_tree", 1, "", "JSON parsing error: " + e.getMessage(), null, null);
        }
    }

    /** Run pytest tests. Args: {"test_file": "tests/test_main.py"} or {"directory": "tests/"} */
    private ToolResult executeRunTests(String args) {
        try {
            jsonMapper.readTree(args); // validate JSON
            String target = "."; // Force auto-discovery

            PythonExecutionResult result = pythonExecutor.runPytest(target);

            ToolResult toolResult = new ToolResult(
                "run_tests",
                result.getExitCode(),
                result.getStdout(),
                result.getStderr(),
                null,
                target
            );

            log.info("[ToolExecutor] run_tests executed on target: {}, exitCode: {}",
                     target, result.getExitCode());

            return toolResult;

        } catch (IllegalArgumentException e) {
            log.error("[ToolExecutor] Invalid args for run_tests: {}", e.getMessage());
            return new ToolResult("run_tests", 1, "", e.getMessage(), null, null);
        } catch (Exception e) {
            log.error("[ToolExecutor] Failed to run tests: {}", e.getMessage(), e);
            return new ToolResult("run_tests", 1, "", "Failed to run tests: " + e.getMessage(), null, null);
        }
    }

    /** Run arbitrary Python script. Args: {"script": "print('hello')"} or {"file": "script.py"} */
    private ToolResult executeRunPython(String args) {
        try {
            JsonNode             json   = jsonMapper.readTree(args);
            PythonExecutionResult result;

            if (json.has("script")) {
                result = pythonExecutor.runScript(requireText(json, "script", "run_python"));
            } else if (json.has("file")) {
                result = pythonExecutor.runFile(requireText(json, "file", "run_python"));
            } else {
                return new ToolResult("run_python", 1, "",
                    "Must provide either 'script' or 'file' parameter", null, null);
            }

            return new ToolResult("run_python", result.getExitCode(),
                result.getStdout(), result.getStderr(), null, null);

        } catch (IllegalArgumentException e) {
            return new ToolResult("run_python", 1, "", e.getMessage(), null, null);
        } catch (Exception e) {
            return new ToolResult("run_python", 1, "", "Failed to run Python: " + e.getMessage(), null, null);
        }
    }
}