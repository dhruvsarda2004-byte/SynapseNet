package com.synapsenet.core.executor;

/**
 * ToolResult - Result of executing a single tool.
 */
public class ToolResult {

    private final String tool;
    private final int exitCode;
    private final String stdout;
    private final String stderr;
    private final String targetFile;
    private final String testTarget;

    public ToolResult(
            String tool,
            int exitCode,
            String stdout,
            String stderr,
            String targetFile,
            String testTarget
    ) {
        this.tool = tool;
        this.exitCode = exitCode;
        this.stdout = stdout != null ? stdout : "";
        this.stderr = stderr != null ? stderr : "";
        this.targetFile = targetFile;
        this.testTarget = testTarget;
    }

    /**
     * Create an error ToolResult.
     * Used when a tool cannot be executed.
     */
    public static ToolResult error(String errorMessage) {
        return new ToolResult(
            "error",
            1,
            "",
            errorMessage,
            null,
            null
        );
    }

    public String getTool() {
        return tool;
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

    public String getTargetFile() {
        return targetFile;
    }

    public String getTestTarget() {
        return testTarget;
    }

    @Override
    public String toString() {
        return String.format(
            "ToolResult{tool='%s', exitCode=%d, target='%s'}",
            tool,
            exitCode,
            targetFile != null ? targetFile : testTarget
        );
    }
}