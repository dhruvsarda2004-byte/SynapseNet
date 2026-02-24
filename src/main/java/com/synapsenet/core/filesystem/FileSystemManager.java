package com.synapsenet.core.filesystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class FileSystemManager {

    private static final Logger log = LoggerFactory.getLogger(FileSystemManager.class);

    private static final long MAX_FILE_SIZE       = 10 * 1024 * 1024;
    private static final int  MAX_SEARCH_RESULTS  = 100;
    private static final int  MAX_TREE_DEPTH      = 10;

    private final Path workspaceRoot;

    public FileSystemManager(
            @Value("${synapsenet.workspace.path}") String workspacePath
    ) {
        this.workspaceRoot = Paths.get(workspacePath).toAbsolutePath().normalize();
        try {
            if (!Files.exists(workspaceRoot)) {
                Files.createDirectories(workspaceRoot);
                log.info("[FileSystem] Created workspace: {}", workspaceRoot);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize workspace: " + workspacePath, e);
        }
        log.info("[FileSystem] Workspace initialized: {}", workspaceRoot);
    }

    /**
     * Returns the absolute path of the workspace root as a String.
     * Used by SimpleTaskOrchestrator for metadata export.
     */
    public String getWorkspacePath() {
        return workspaceRoot.toString();
    }

    // ================================================================
    // Snapshot / Restore
    // ================================================================

    public WorkspaceSnapshot snapshotWorkspace(Predicate<Path> predicate)
            throws FileSystemException {

        Map<String, String> files = new HashMap<>();

        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            List<Path> toSnapshot = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> !isIgnoredFile(p))
                    .filter(p -> predicate.test(workspaceRoot.relativize(p)))
                    .collect(Collectors.toList());

            for (Path absolute : toSnapshot) {
                String relative = workspaceRoot.relativize(absolute).toString();
                long fileSize = Files.size(absolute);
                if (fileSize > MAX_FILE_SIZE) {
                    throw new FileSystemException(
                            "Snapshot aborted — file too large: " + relative +
                            " (" + fileSize + " bytes, max: " + MAX_FILE_SIZE + ")"
                    );
                }
                files.put(relative, Files.readString(absolute, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new FileSystemException("Failed to snapshot workspace", e);
        }

        log.info("[FileSystem] Snapshot taken: {} files", files.size());
        return new WorkspaceSnapshot(Collections.unmodifiableMap(files), predicate);
    }

    public void restoreWorkspace(WorkspaceSnapshot snapshot) throws FileSystemException {

        Map<String, String> snapshotFiles = snapshot.getFiles();
        Predicate<Path>     predicate     = snapshot.getPredicate();

        List<String> currentManagedFiles;
        try (Stream<Path> paths = Files.walk(workspaceRoot)) {
            currentManagedFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> !isIgnoredFile(p))
                    .filter(p -> predicate.test(workspaceRoot.relativize(p)))
                    .map(p -> workspaceRoot.relativize(p).toString())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new FileSystemException("Restore failed: could not enumerate workspace files", e);
        }

        for (Map.Entry<String, String> entry : snapshotFiles.entrySet()) {
            writeFile(entry.getKey(), entry.getValue());
        }

        Set<String> snapshotKeys = snapshotFiles.keySet();
        for (String currentFile : currentManagedFiles) {
            if (!snapshotKeys.contains(currentFile)) {
                try {
                    Files.delete(resolveSafePath(currentFile));
                    log.info("[FileSystem] Restore: deleted repair-created file '{}'", currentFile);
                } catch (IOException e) {
                    throw new FileSystemException(
                            "Restore failed: could not delete: " + currentFile, e);
                }
            }
        }

        log.info("[FileSystem] Workspace restored to snapshot ({} files)", snapshotFiles.size());
    }

    // ================================================================
    // Standard File Operations
    // ================================================================

    public String readFile(String relativePath) throws FileSystemException {
        Path targetPath = resolveSafePath(relativePath);
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
                         .filter(p -> !isIgnoredFile(p))
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
            return paths.map(p -> p.getFileName().toString()).collect(Collectors.toList());
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

    public long getFileSize(String relativePath) throws FileSystemException {
        try { return Files.size(resolveSafePath(relativePath)); }
        catch (IOException e) { throw new FileSystemException("Failed to get file size: " + relativePath, e); }
    }

    // ================================================================
    // Private helpers
    // ================================================================

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

    private void buildTree(Path dir, String prefix, int depth, StringBuilder output)
            throws IOException {
        if (depth <= 0) return;
        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> entries = paths.filter(p -> !isIgnoredFile(p))
                                      .sorted().collect(Collectors.toList());
            for (int i = 0; i < entries.size(); i++) {
                Path    entry  = entries.get(i);
                boolean isLast = (i == entries.size() - 1);
                output.append(prefix).append(isLast ? "└── " : "├── ").append(entry.getFileName());
                if (Files.isDirectory(entry)) {
                    output.append("/\n");
                    buildTree(entry, prefix + (isLast ? "    " : "│   "), depth - 1, output);
                } else {
                    output.append("\n");
                }
            }
        }
    }

    private boolean isIgnoredFile(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".") || name.equals("__pycache__") ||
               name.equals("node_modules") || name.endsWith(".pyc") || name.endsWith(".class");
    }

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