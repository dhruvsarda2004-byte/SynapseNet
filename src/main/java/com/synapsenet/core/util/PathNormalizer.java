package com.synapsenet.core.util;

import java.nio.file.Paths;

/**
 * PathNormalizer — single source of truth for workspace-relative path canonicalization.
 *
 * Invariant: every path stored in SharedState, used as a snapshot key, or passed to
 * FileSystemManager must have been processed by normalize() exactly once.
 *
 * Rules applied (in order):
 *   1. null  → null  (safe pass-through)
 *   2. Backslashes replaced with forward slashes  (Windows LLM output tolerance)
 *   3. Paths.get(p).normalize() — collapses ./, ../, and // via the JVM's own
 *      path canonicalization. Handles ././, a/../b, and any combination without
 *      bespoke string logic.
 *   4. Re-apply backslash->slash in case the JVM toString() emitted \ (Windows JVM)
 *   5. Trailing slash stripped  (directory paths must not end with '/')
 *
 * Why Paths.get().normalize() instead of regex:
 *   The JVM collapses all of ./, ../, and duplicate separators in one pass,
 *   deterministically, without edge cases. Regex alternatives require maintaining a
 *   growing list of patterns as new LLM output variants are discovered.
 *
 * This class is intentionally final with no instance state.
 */
public final class PathNormalizer {

    private PathNormalizer() { /* utility class — no instances */ }

    /**
     * Canonicalize a workspace-relative path.
     *
     * @param path raw path from LLM output, tool args, or test results — may be null
     * @return canonical path, or null if input was null
     */
    public static String normalize(String path) {
        if (path == null) return null;

        path = path.trim();

        // 🔴 CRITICAL: preserve logical root sentinel
        if (path.equals(".")) {
            return ".";
        }

        // Prevent empty-string propagation into filesystem layer
        if (path.isEmpty()) {
            return "";
        }

        // 1. Normalize separators for cross-platform consistency
        String p = path.replace('\\', '/');

        // 2. Collapse ./, ../, and duplicate separators
        p = Paths.get(p).normalize().toString();

        // 3. Re-normalize separators in case JVM emitted backslashes
        p = p.replace('\\', '/');

        // 🔴 CRITICAL: normalize "." again after JVM normalization
        if (p.equals(".")) {
            return ".";
        }

        // 4. Strip trailing slash (but never reduce to empty)
        if (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }

        return p;
    }

    /**
     * Convenience: normalize and compare two paths for equality.
     * Both inputs are normalized before comparison.
     *
     * @return true iff the canonical forms of a and b are equal
     */
    public static boolean equivalent(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return normalize(a).equals(normalize(b));
    }
}