package com.synapsenet.core.filesystem;

import java.nio.file.Path;
import java.util.Set;

/**
 * FileSystemFilter — single source of truth for the managed workspace domain.
 *
 * A "managed" file is one that:
 *   (a) lives entirely outside any excluded directory (checked at every component of its
 *       workspace-relative path), AND
 *   (b) has a filename that is not an ignored pattern (dot-files, compiled artifacts).
 *
 * -----------------------------------------------------------------------
 * WHY THIS CLASS EXISTS
 * -----------------------------------------------------------------------
 * Before this class, exclusion logic was duplicated four ways:
 *
 *   1. snapshotWorkspace   — local Set<String> skippedDirs  +  isIgnoredFile()
 *   2. restoreWorkspace    — EXCLUDED_DIRS field              +  isIgnoredFile()
 *   3. getAllFilePaths      — isIgnoredFile() only            (missing dir check)
 *   4. hashEntireWorkspace — nothing                         (root cause of H-1)
 *   5. grep / buildTree    — isIgnoredFile() only            (missing dir check)
 *
 * This fragmentation meant hashEntireWorkspace and restoreWorkspace operated over
 * different domains.  The hash invariant T0 == AFTER_RESTORE was therefore
 * mathematically invalid: restore only touched managed files, but the hash covered
 * ALL files including __pycache__ bytecode, .venv internals, and .git objects.
 * Any patch that added a new .py file — and thus caused Python to emit a new .pyc —
 * would change the hash domain without changing the restore domain, causing the
 * invariant check to throw IllegalStateException on every REPLAN.
 *
 * FileSystemFilter eliminates that drift by providing one predicate used everywhere.
 *
 * -----------------------------------------------------------------------
 * DESIGN CONSTRAINTS (do not relax without audit)
 * -----------------------------------------------------------------------
 *
 * 1. Path objects only — no String normalization.
 *    Normalization is PathNormalizer's responsibility (string keys in SharedState /
 *    snapshot maps).  This class operates during traversal, before keys are formed.
 *    Mixing the two concerns risks double-normalization and cognitive coupling.
 *
 * 2. Instance bound to workspaceRoot.
 *    relativize(absolute) requires a fixed root.  Passing workspaceRoot at every
 *    call site would scatter the invariant.  One instance per FileSystemManager.
 *
 * 3. EXCLUDED_DIR_NAMES is checked at every component of the relative path.
 *    A file at .venv/lib/python3.10/site-packages/requests/__init__.py must be
 *    excluded even though its own filename is not a dot-file and does not end in .pyc.
 *    Filename-level checking alone does not achieve this.
 *
 * 4. EXCLUDED_DIR_NAMES is the authoritative excluded-directory set.
 *    FileSystemManager must not define EXCLUDED_DIRS, skippedDirs, or any inline
 *    Set<String> with overlapping intent.  All such definitions are deleted when this
 *    class is wired in (Step 2).
 *
 * 5. isManaged() is safe to call on both regular files and directories.
 *    In buildTree(), the filter is applied to all directory entries.  A directory
 *    whose name is in EXCLUDED_DIR_NAMES returns false, preventing descent.
 *    Callers that want only regular files should pre-filter with Files::isRegularFile
 *    before calling isManaged() — this class does not assert regularity.
 *
 * -----------------------------------------------------------------------
 * EXCLUDED DIRECTORY NAMES — rationale for each entry
 * -----------------------------------------------------------------------
 *
 *   .venv            Python virtual environment.  Contains thousands of site-package
 *                    files.  Patching anything inside .venv is never correct.
 *                    Without this, getAllFilePaths() floods the workspace index with
 *                    .venv paths, causing setFailingArtifact() to accept hallucinated
 *                    paths like "ratelimit/.venv/lib/.../token.py" as valid artifacts.
 *
 *   .git             Git object store.  Binary blobs, pack files, COMMIT_EDITMSG.
 *                    Including these in the hash domain produces a hash that changes
 *                    whenever Git operations occur (e.g., a commit during the run).
 *
 *   __pycache__      Python bytecode cache.  These files are created by the Python
 *                    interpreter whenever it imports a module.  They change on every
 *                    test run and are the primary cause of H-1: a patch that adds
 *                    src/bar.py causes Python to emit __pycache__/bar.cpython-310.pyc,
 *                    which hashEntireWorkspace() included but restoreWorkspace() skipped.
 *
 *   .pytest_cache    pytest result cache.  Written after every test run.  Including it
 *                    in the hash makes T0 sensitive to pytest invocation order.
 *
 *   node_modules     JavaScript dependency directory.  Excluded by name at the component
 *                    level rather than filename level so that all transitive contents
 *                    (not just the directory entry itself) are excluded.
 *
 * -----------------------------------------------------------------------
 * IGNORED FILENAME PATTERNS — rationale
 * -----------------------------------------------------------------------
 *
 *   startsWith(".")  Dot-files: .DS_Store, .env, .envrc, .editorconfig, etc.
 *                    These are IDE / OS metadata.  They also catch directory entries
 *                    whose names begin with "." (e.g., .venv at the leaf level) as a
 *                    second safety net, though the component check is the primary guard.
 *
 *   endsWith(".pyc") Compiled Python bytecode.  Not caught by the __pycache__ component
 *                    check if a .pyc lives at the workspace root (unusual but possible).
 *                    Belt-and-suspenders: exclude by extension regardless of directory.
 *
 *   endsWith(".class") Compiled Java bytecode.  Same reasoning as .pyc.
 *
 * Note: "node_modules" and "__pycache__" are NOT listed here.  As filenames they are
 * covered by the component check.  Duplicating them here would be a maintenance hazard.
 */
public final class FileSystemFilter {

    // -------------------------------------------------------------------------
    // Excluded directory names — checked at every component of the relative path
    // -------------------------------------------------------------------------

    static final Set<String> EXCLUDED_DIR_NAMES = Set.of(
        ".venv",
        ".git",
        "__pycache__",
        ".pytest_cache",
        "node_modules"
    );

    // -------------------------------------------------------------------------
    // Instance state
    // -------------------------------------------------------------------------

    private final Path workspaceRoot;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Create a filter bound to the given workspace root.
     *
     * @param workspaceRoot absolute, normalized path to the workspace root.
     *                      Must be the same root used by FileSystemManager.
     */
    public FileSystemFilter(Path workspaceRoot) {
        if (workspaceRoot == null) throw new IllegalArgumentException("workspaceRoot must not be null");
        this.workspaceRoot = workspaceRoot;
    }

    // -------------------------------------------------------------------------
    // Public API — single method, single contract
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given absolute path is within the managed workspace domain.
     *
     * A path is managed if:
     *   (1) No component of its workspace-relative path is an excluded directory name.
     *   (2) Its filename does not match an ignored filename pattern.
     *
     * This method does NOT assert that the path is a regular file.
     * Callers that want only files should pre-filter with {@code Files::isRegularFile}.
     *
     * @param absolute absolute path to test; must be under workspaceRoot
     * @return true if the path belongs to the managed domain
     * @throws IllegalArgumentException if absolute is not under workspaceRoot
     */
    public boolean isManaged(Path absolute) {
        if (!absolute.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException(
                "Path is not under workspace root: " + absolute + " (root: " + workspaceRoot + ")"
            );
        }
        Path relative = workspaceRoot.relativize(absolute);
        return !hasExcludedComponent(relative) && !isIgnoredFilename(absolute.getFileName());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if any component of the relative path is an excluded directory name.
     *
     * Each component of the relative path is checked independently so that deeply nested
     * paths like {@code .venv/lib/python3.10/site-packages/foo.py} are correctly excluded
     * on the first component, without needing to enumerate all possible nesting depths.
     */
    private boolean hasExcludedComponent(Path relative) {
        for (Path component : relative) {
            if (EXCLUDED_DIR_NAMES.contains(component.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the filename matches an ignored pattern.
     *
     * Operates on the final path component only.  Directory exclusion by component
     * (hasExcludedComponent) handles intermediate directories.
     *
     * @param filename the {@code getFileName()} component of the path
     */
    private boolean isIgnoredFilename(Path filename) {
        if (filename == null) return false;
        String name = filename.toString();
        return name.startsWith(".")
            || name.endsWith(".pyc")
            || name.endsWith(".class");
    }

    // -------------------------------------------------------------------------
    // toString — for debug logging
    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "FileSystemFilter{root=" + workspaceRoot + "}";
    }
}