package com.synapsenet.core.filesystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.synapsenet.core.util.PathNormalizer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.nio.charset.MalformedInputException;
import java.util.Set;

@Component
public class FileSystemManager {

    private static final Logger log = LoggerFactory.getLogger(FileSystemManager.class);

    private static final long MAX_FILE_SIZE       = 10 * 1024 * 1024;
    private static final int  MAX_SEARCH_RESULTS  = 100;
    private static final int  MAX_TREE_DEPTH      = 10;

    // [Step 2] EXCLUDED_DIRS deleted.
    // Domain exclusion is now the sole responsibility of FileSystemFilter.
    // No inline exclusion sets permitted anywhere in this class.

    private final Path             workspaceRoot;
    private final FileSystemFilter filter;           // [Step 2] single domain predicate
    private final String           pythonExecutable; // [Step 5] used by validatePythonSyntax

    public FileSystemManager(
            @Value("${synapsenet.workspace.path}")    String workspacePath,
            @Value("${synapsenet.python.executable}") String pythonExecutable
    ) {
        this.workspaceRoot    = Paths.get(workspacePath).toAbsolutePath().normalize();
        this.filter           = new FileSystemFilter(workspaceRoot); // [Step 2] bound to same root
        this.pythonExecutable = pythonExecutable;                    // [Step 5]
        try {
            if (!Files.exists(workspaceRoot)) {
                Files.createDirectories(workspaceRoot);
                log.info("[FileSystem] Created workspace: {}", workspaceRoot);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize workspace: " + workspacePath, e);
        }
        log.info("[FileSystem] Workspace initialized: {}", workspaceRoot);
        log.info("[FileSystem] Domain filter: {}", filter);
        log.info("[FileSystem] Python validator: {}", pythonExecutable);
    }

    /**
     * Returns the absolute path of the workspace root as a String.
     * Used by SimpleTaskOrchestrator for metadata export.
     */
    public String getWorkspacePath() {
        return workspaceRoot.toString();
    }

    // ================================================================
    // Hash
    // ================================================================

    /**
     * Hash the entire workspace under {@code root}.
     *
     * [Step 3] Hash domain now matches restore domain via filter::isManaged.
     * Only files passing FileSystemFilter.isManaged() are included in the digest.
     * This makes the T0 == AFTER_RESTORE invariant in SimpleTaskOrchestrator
     * mathematically valid: both hash and restore operate over the same managed
     * file set, excluding __pycache__, .venv, .git, .pytest_cache, and compiled
     * artifacts.  The H-1 crash (IllegalStateException on REPLAN after any patch
     * introducing a new .py file) is resolved.
     */
    public String hashEntireWorkspace(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            Files.walk(root)
                 .filter(Files::isRegularFile)
                 .filter(filter::isManaged)                       // [Step 3] H-1 fix
                 .sorted()
                 .forEach(path -> {
                     try {
                         digest.update(Files.readAllBytes(path));
                     } catch (IOException e) {
                         throw new RuntimeException(e);
                     }
                 });

            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) sb.append(String.format("%02x", b));
            return sb.toString();

        } catch (Exception e) {
            throw new RuntimeException("Failed to hash workspace", e);
        }
    }

    // ================================================================
    // Snapshot / Restore
    // ================================================================

    /**
     * Capture a snapshot of files matching the given predicate.
     *
     * [PathNormalizer] Snapshot keys are stored as normalized paths (forward slashes,
     * no ./ or ../, no duplicate separators). This guarantees that snapshot keys are
     * comparable to state paths (which are also normalized via PathNormalizer) and to
     * the currentManagedFiles list in restoreWorkspace() (normalized identically).
     *
     * [Step 2] Domain filtering now delegated entirely to FileSystemFilter.
     * The local skippedDirs Set and the hand-rolled component loop are deleted.
     * The inline !isIgnoredFile(p) filter is deleted.
     * Both are replaced by a single filter::isManaged call, which applies
     * component-level and filename-level exclusion in one pass.
     */
    public WorkspaceSnapshot snapshotWorkspace(Predicate<Path> predicate)
            throws FileSystemException {

        Map<String, String> files = new LinkedHashMap<>();

        // [Step 2] Removed: local skippedDirs Set and manual component loop.
        // [Step 2] Removed: .filter(p -> !isIgnoredFile(p))
        // Replaced by: .filter(filter::isManaged)
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            List<Path> toSnapshot = paths
                    .filter(Files::isRegularFile)
                    .filter(filter::isManaged)                                     // [Step 2]
                    .filter(p -> predicate.test(workspaceRoot.relativize(p)))
                    .collect(Collectors.toList());

            for (Path absolute : toSnapshot) {
                String relative = PathNormalizer.normalize(
                        workspaceRoot.relativize(absolute).toString());
                long fileSize = Files.size(absolute);
                if (fileSize > MAX_FILE_SIZE) {
                    throw new FileSystemException(
                            "Snapshot aborted — file too large: " + relative +
                            " (" + fileSize + " bytes, max: " + MAX_FILE_SIZE + ")"
                    );
                }
                try {
                    byte[] bytes = Files.readAllBytes(absolute);
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    files.put(relative, content);
                } catch (MalformedInputException e) {
                    log.debug("[FileSystem] Skipping non-text file during snapshot: {}", relative);
                }
            }
        } catch (IOException e) {
            throw new FileSystemException("Failed to snapshot workspace", e);
        }

        log.info("[FileSystem] Snapshot taken: {} files", files.size());
        files.forEach((k, v) -> log.info("[FileSystem] Snapshot file: {}", k));
        return new WorkspaceSnapshot(Collections.unmodifiableMap(files), predicate);
    }

    /**
     * Restore workspace files to the state captured in the snapshot.
     *
     * Files in the snapshot are written back. Files that exist in the workspace
     * but were not in the snapshot (i.e. created during a patch cycle) are deleted.
     *
     * [PathNormalizer] currentManagedFiles are normalized to match snapshot keys,
     * which are also normalized. This makes snapshotKeys.contains(currentFile)
     * deterministic regardless of OS path separator.
     *
     * [Step 2] Domain filtering now delegated entirely to FileSystemFilter.
     * The EXCLUDED_DIRS component loop is deleted and replaced by filter::isManaged.
     * The inline !isIgnoredFile(p) filter is deleted.
     *
     * [Step 4] Deletion is aggregated: all orphan files are attempted, failures are
     * collected, and a single FileSystemException is thrown after the loop if any
     * deletion failed. This prevents a single locked or missing file from leaving
     * other orphan patch files on disk.
     */
    public void restoreWorkspace(WorkspaceSnapshot snapshot) throws FileSystemException {

        Map<String, String> snapshotFiles = snapshot.getFiles();
        Predicate<Path>     predicate     = snapshot.getPredicate();

        // [Step 2] Removed: EXCLUDED_DIRS component loop.
        // [Step 2] Removed: .filter(p -> !isIgnoredFile(p))
        // Replaced by: .filter(filter::isManaged)
        List<String> currentManagedFiles;
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            currentManagedFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(filter::isManaged)                                     // [Step 2]
                    .filter(p -> predicate.test(workspaceRoot.relativize(p)))
                    .map(p -> PathNormalizer.normalize(workspaceRoot.relativize(p).toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new FileSystemException("Restore failed: could not enumerate workspace files", e);
        }

        for (Map.Entry<String, String> entry : snapshotFiles.entrySet()) {
            writeFile(entry.getKey(), entry.getValue());
        }

        // [Step 4] Aggregated deletion.
        // All orphan files (managed files not in the snapshot) are attempted.
        // Failures are collected rather than thrown immediately so that as many
        // orphan files as possible are removed before the error is reported.
        // If any deletions failed, a single FileSystemException is thrown at the end.
        // The workspace may still contain some orphans in that case, but the surface
        // area is minimized compared to fail-fast behavior.
        Set<String> snapshotKeys = snapshotFiles.keySet();
        List<String> failedDeletions = new ArrayList<>();
        for (String currentFile : currentManagedFiles) {
            if (!snapshotKeys.contains(currentFile)) {
                try {
                    Files.delete(resolveSafePath(currentFile));
                    log.info("[FileSystem] Restore: deleted repair-created file '{}'", currentFile);
                } catch (IOException e) {
                    log.warn("[FileSystem] Restore: failed to delete '{}': {}", currentFile, e.getMessage());
                    failedDeletions.add(currentFile);
                }
            }
        }
        if (!failedDeletions.isEmpty()) {
            throw new FileSystemException(
                "Restore incomplete — could not delete: " + String.join(", ", failedDeletions));
        }

        log.info("[FileSystem] Workspace restored to snapshot ({} files)", snapshotFiles.size());
    }

    // ================================================================
    // Standard File Operations
    // ================================================================

    public String readFile(String relativePath) throws FileSystemException {
        Path targetPath = resolveSafePath(relativePath);

        // [Fix 3] Basename fallback removed. Strict path equality required.
        // If the file is not found at the exact path, fail immediately.
        // Alias resolution caused path aliasing and broke snapshot comparisons.

        log.info("[FileSystem] Reading file: {}", relativePath);
        try {
            long fileSize = Files.size(targetPath);
            if (fileSize > MAX_FILE_SIZE)
                throw new FileSystemException("File too large: " + fileSize + " bytes");
            String content = Files.readString(targetPath, StandardCharsets.UTF_8);
            log.info("[FileSystem] Read {} bytes from {}", content.length(), relativePath);
            return content;
        } catch (IOException e) {
            throw new FileSystemException("Failed to read file: " + relativePath, e);
        }
    }

    public List<String> readLines(String relativePath, int startLine, int endLine)
            throws FileSystemException {
        Path targetPath = resolveSafePath(relativePath);
        log.info("[FileSystem] Reading lines {}-{} from {}", startLine, endLine, relativePath);
        try {
            List<String> allLines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);
            int start = Math.max(0, startLine - 1);
            int end   = endLine == -1 ? allLines.size() : Math.min(allLines.size(), endLine);
            return allLines.subList(start, end);
        } catch (IOException e) {
            throw new FileSystemException("Failed to read lines from: " + relativePath, e);
        }
    }

    public void writeFile(String relativePath, String content) throws FileSystemException {
        Path targetPath = resolveSafePath(relativePath);
        validatePythonSyntax(relativePath, content);  // [Step 5] validate before any disk mutation
        log.info("[FileSystem] Writing {} bytes to {}", content.length(), relativePath);
        try {
            Path parent = targetPath.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            Files.writeString(targetPath, content, StandardCharsets.UTF_8);
            log.info("[FileSystem] Successfully wrote to {}", relativePath);
        } catch (IOException e) {
            throw new FileSystemException("Failed to write file: " + relativePath, e);
        }
    }

    public List<SearchResult> grep(String pattern, String relativePath) throws FileSystemException {
        Path targetPath = resolveSafePath(relativePath);
        log.info("[FileSystem] Searching for '{}' in {}", pattern, relativePath);
        List<SearchResult> results  = new ArrayList<>();
        Pattern compiledPattern     = Pattern.compile(pattern);
        try {
            if (Files.isDirectory(targetPath)) {
                try (Stream<Path> paths = Files.walk(targetPath, MAX_TREE_DEPTH)) {
                    paths.filter(Files::isRegularFile)
                         .filter(filter::isManaged)                               // [Step 2]
                         .sorted()
                         .forEach(file -> {
                             try { results.addAll(searchInFile(file, compiledPattern)); }
                             catch (IOException e) {
                                 log.warn("[FileSystem] Failed to search {}: {}", file, e.getMessage());
                             }
                         });
                }
            } else {
                results.addAll(searchInFile(targetPath, compiledPattern));
            }
            log.info("[FileSystem] Found {} matches", results.size());
            return results.stream().limit(MAX_SEARCH_RESULTS).collect(Collectors.toList());
        } catch (IOException e) {
            throw new FileSystemException("Failed to search in: " + relativePath, e);
        }
    }

    public List<String> listFiles(String relativePath) throws FileSystemException {
        Path targetPath = resolveSafePath(relativePath);
        log.info("[FileSystem] Listing files in {}", relativePath);
        if (!Files.isDirectory(targetPath))
            throw new FileSystemException("Not a directory: " + relativePath);
        try (Stream<Path> paths = Files.list(targetPath)) {
            return paths
                    .sorted()
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new FileSystemException("Failed to list directory: " + relativePath, e);
        }
    }

    public String getFileTree(String relativePath, int maxDepth) throws FileSystemException {
        Path targetPath = resolveSafePath(relativePath);
        log.info("[FileSystem] Building file tree for {}", relativePath);
        if (!Files.isDirectory(targetPath))
            throw new FileSystemException("Not a directory: " + relativePath);
        StringBuilder tree = new StringBuilder();
        tree.append(relativePath).append("/\n");
        try {
            buildTree(targetPath, "", Math.min(maxDepth, MAX_TREE_DEPTH), tree);
            return tree.toString();
        } catch (IOException e) {
            throw new FileSystemException("Failed to build tree: " + relativePath, e);
        }
    }

    public boolean fileExists(String relativePath) {
        try { return Files.exists(resolveSafePath(relativePath)); }
        catch (FileSystemException e) { return false; }
    }

    /**
     * Return all regular file paths in the workspace as normalized relative strings.
     *
     * Used by Fix 5 (SharedState workspace index) — called from RealToolExecutor
     * after file_tree or list_files succeeds. Deterministic: uses Files.walk,
     * not ASCII tree parsing.
     *
     * [Step 2] Replaced .filter(p -> !isIgnoredFile(p)) with .filter(filter::isManaged).
     * This closes the domain gap that caused the workspace file index to include
     * .venv/lib/.../*.py paths, which allowed setFailingArtifact() to accept
     * hallucinated artifact paths rooted inside .venv.
     */
    public List<String> getAllFilePaths() throws FileSystemException {
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(filter::isManaged)                                     // [Step 2]
                    .map(p -> PathNormalizer.normalize(workspaceRoot.relativize(p).toString()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new FileSystemException("Failed to enumerate workspace files", e);
        }
    }

    public long getFileSize(String relativePath) throws FileSystemException {
        try { return Files.size(resolveSafePath(relativePath)); }
        catch (IOException e) { throw new FileSystemException("Failed to get file size: " + relativePath, e); }
    }

    // ================================================================
    // Private helpers
    // ================================================================

    /**
     * [Step 5] Pre-write Python syntax gate.
     *
     * Validates proposed file content against the workspace Python interpreter
     * before any bytes are written to disk. Applies only to .py files.
     *
     * Mechanism: spawns the configured Python executable with ast.parse fed via
     * stdin. No temp files, no disk writes, no race conditions. If the content
     * is syntactically invalid, throws FileSystemException immediately — the
     * file is never written, no directories are created, no snapshot is needed,
     * no REPLAN is triggered. The exception message carries the Python error
     * (line number + description) which RealToolExecutor surfaces as a tool error
     * into the LLM's next iteration prompt.
     *
     * Sequencing invariant: called after resolveSafePath (so relativePath is
     * known) but before createDirectories and writeString (so no filesystem
     * mutation occurs on failure).
     */
    private void validatePythonSyntax(String relativePath, String content)
            throws FileSystemException {
        if (!relativePath.endsWith(".py")) return;

        ProcessBuilder pb = new ProcessBuilder(
                pythonExecutable, "-c", "import ast, sys; ast.parse(sys.stdin.read())"
        );
        pb.redirectErrorStream(true); // merge stderr into stdout; read once after waitFor

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // If we cannot spawn the validator at all, fail safe: reject the write.
            throw new FileSystemException(
                    "[Validator] Could not start Python for syntax check of '" + relativePath + "'", e);
        }

        // Write proposed content to stdin and close the stream so Python sees EOF.
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            process.destroyForcibly();
            throw new FileSystemException(
                    "[Validator] Failed to pipe content to Python for '" + relativePath + "'", e);
        }

        // Wait up to 5 seconds — ast.parse on any realistic source file is < 50ms.
        boolean finished;
        try {
            finished = process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new FileSystemException(
                    "[Validator] Syntax validation interrupted for '" + relativePath + "'");
        }

        if (!finished) {
            process.destroyForcibly();
            throw new FileSystemException(
                    "[Validator] Syntax validation timed out for '" + relativePath + "'");
        }

        if (process.exitValue() != 0) {
            String errorOutput;
            try {
                errorOutput = new String(
                        process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                errorOutput = "(could not read Python error output)";
            }
            log.warn("[FileSystem] Python syntax validation FAILED for '{}': {}", relativePath, errorOutput);
            throw new FileSystemException(
                    "Python syntax error in proposed content for '" + relativePath + "': " + errorOutput);
        }

        log.debug("[FileSystem] Python syntax validation passed for '{}'", relativePath);
    }

    private Path resolveSafePath(String relativePath) throws FileSystemException {
        if (relativePath == null || relativePath.trim().isEmpty())
            throw new FileSystemException("Path cannot be empty");
        Path resolved = workspaceRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(workspaceRoot))
            throw new FileSystemException("Path traversal attempt detected: " + relativePath);
        return resolved;
    }

    private List<SearchResult> searchInFile(Path file, Pattern pattern) throws IOException {
        List<SearchResult> results      = new ArrayList<>();
        List<String>       lines        = Files.readAllLines(file, StandardCharsets.UTF_8);
        String             relFilePath  = workspaceRoot.relativize(file).toString();
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find())
                results.add(new SearchResult(relFilePath, i + 1, lines.get(i)));
        }
        return results;
    }

    /**
     * [Step 2] Replaced .filter(p -> !isIgnoredFile(p)) with .filter(filter::isManaged).
     *
     * filter::isManaged returns false for:
     *   - Any entry whose name is in EXCLUDED_DIR_NAMES (e.g. __pycache__, .venv)
     *   - Any entry whose filename starts with "." or ends with .pyc / .class
     *
     * This means buildTree will not descend into excluded directories at all —
     * they are removed from the entries list before the loop, so
     * buildTree() is never called recursively on them.
     *
     * Note: filter::isManaged is called on both files and directories here.
     * That is intentional — isManaged() does not assert isRegularFile, and
     * buildTree needs to filter directory entries to decide whether to recurse.
     */
    private void buildTree(Path dir, String prefix, int depth, StringBuilder output)
            throws IOException {
        if (depth <= 0) return;
        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> entries = paths
                    .filter(filter::isManaged)                                     // [Step 2]
                    .sorted()
                    .collect(Collectors.toList());
            for (int i = 0; i < entries.size(); i++) {
                Path    entry  = entries.get(i);
                boolean isLast = (i == entries.size() - 1);
                output.append(prefix)
                      .append(isLast ? "\u2514\u2500\u2500 " : "\u251C\u2500\u2500 ")
                      .append(entry.getFileName());
                if (Files.isDirectory(entry)) {
                    output.append("/\n");
                    buildTree(entry, prefix + (isLast ? "    " : "\u2502   "), depth - 1, output);
                } else {
                    output.append("\n");
                }
            }
        }
    }

    // [Step 2] isIgnoredFile() deleted.
    // All callers replaced with filter::isManaged.
    // If you see a compilation error referencing isIgnoredFile(), that call site
    // was missed and must be updated before the build can proceed.

    // ================================================================
    // Inner classes
    // ================================================================

    public static final class WorkspaceSnapshot {
        private final Map<String, String> files;
        private final Predicate<Path>     predicate;

        private WorkspaceSnapshot(Map<String, String> files, Predicate<Path> predicate) {
            this.files     = files;
            this.predicate = predicate;
        }

        public Map<String, String> getFiles()     { return files; }
        public Predicate<Path>     getPredicate() { return predicate; }
        public int                 size()         { return files.size(); }

        @Override public String toString() { return "WorkspaceSnapshot{files=" + files.size() + "}"; }
    }

    public static class SearchResult {
        private final String filePath;
        private final int    lineNumber;
        private final String lineContent;

        public SearchResult(String filePath, int lineNumber, String lineContent) {
            this.filePath    = filePath;
            this.lineNumber  = lineNumber;
            this.lineContent = lineContent;
        }

        public String getFilePath()    { return filePath; }
        public int    getLineNumber()  { return lineNumber; }
        public String getLineContent() { return lineContent; }

        @Override public String toString() {
            return String.format("%s:%d: %s", filePath, lineNumber, lineContent);
        }
    }

    public static class FileSystemException extends Exception {
        public FileSystemException(String message)                  { super(message); }
        public FileSystemException(String message, Throwable cause) { super(message, cause); }
    }
}