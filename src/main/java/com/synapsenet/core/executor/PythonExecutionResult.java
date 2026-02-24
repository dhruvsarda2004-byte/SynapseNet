package com.synapsenet.core.executor;

/**
 * PythonExecutionResult - Encapsulates the result of executing a Python script or test.
 * 
 * Fields:
 *   - exitCode: Process exit code (0 for success, non-zero for failure)
 *   - stdout: Standard output from the process
 *   - stderr: Standard error from the process (or merged output if streams were combined)
 *   - elapsedTimeMs: Wall-clock time the execution took
 */
public class PythonExecutionResult {

    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final long elapsedTimeMs;

    public PythonExecutionResult(int exitCode, String stdout, String stderr, long elapsedTimeMs) {
        this.exitCode = exitCode;
        this.stdout = stdout != null ? stdout : "";
        this.stderr = stderr != null ? stderr : "";
        this.elapsedTimeMs = elapsedTimeMs;
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getStdout() {
        return stdout;
    }

    public String getStderr() {
        return stderr;
    }

    public long getElapsedTimeMs() {
        return elapsedTimeMs;
    }

    /**
     * Create an error result.
     * Used when execution cannot proceed (e.g., temp file creation fails).
     */
    public static PythonExecutionResult error(String errorMessage) {
        return new PythonExecutionResult(-2, "", errorMessage, 0);
    }

    @Override
    public String toString() {
        return String.format(
            "PythonExecutionResult{exitCode=%d, stdoutLen=%d, stderrLen=%d, elapsedMs=%d}",
            exitCode,
            stdout.length(),
            stderr.length(),
            elapsedTimeMs
        );
    }
}