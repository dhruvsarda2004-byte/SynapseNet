package com.synapsenet.core.executor;

import java.util.ArrayList;
import java.util.List;

/**
 * ExecutionResult - Result of executing a planner task.
 *
 * Contains:
 * - Task description
 * - Tool execution results
 * - Test results (if tests were run)
 * - List of modified files
 */
public class ExecutionResult {

    private final String taskDescription;
    private final List<ToolResult> toolResults;
    private final TestResults testResults;
    private final List<String> modifiedFiles;

    public ExecutionResult(
            String taskDescription,
            List<ToolResult> toolResults,
            TestResults testResults,
            List<String> modifiedFiles
    ) {
        this.taskDescription = taskDescription;
        this.toolResults = toolResults != null ? toolResults : new ArrayList<>();
        this.testResults = testResults != null ? testResults : TestResults.notRun();
        this.modifiedFiles = modifiedFiles != null ? modifiedFiles : new ArrayList<>();
    }

    /**
     * Create an error ExecutionResult when task cannot be executed.
     * Used by phase enforcement guards.
     */
    public static ExecutionResult error(String errorMessage) {
        ToolResult errorResult = ToolResult.error(errorMessage);
        return new ExecutionResult(
            "ERROR: " + errorMessage,
            List.of(errorResult),
            TestResults.notRun(),
            new ArrayList<>()
        );
    }

    public String getTaskDescription() {
        return taskDescription;
    }

    public List<ToolResult> getToolResults() {
        return toolResults;
    }

    public TestResults getTestResults() {
        return testResults;
    }

    public List<String> getModifiedFiles() {
        return modifiedFiles;
    }

    /**
     * Check if any tool execution failed.
     */
    public boolean hasToolErrors() {
        return toolResults.stream().anyMatch(r -> r.getExitCode() != 0);
    }

    /**
     * Alias for hasToolErrors() - used by MediatorAgent.
     */
    public boolean hasErrors() {
        return hasToolErrors();
    }

    /**
     * Get error messages from failed tools.
     */
    public String getToolErrors() {
        return toolResults.stream()
                .filter(r -> r.getExitCode() != 0)
                .map(r -> r.getStderr() != null && !r.getStderr().isEmpty() 
                        ? r.getStderr() 
                        : r.getStdout())
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();
    }

    /**
     * Alias for getToolErrors(), capped at 15 lines — used by CriticAgent.
     *
     * WHY: getToolErrors() returns the full stderr of failed tools. When run_tests
     * fails, this is the entire pytest output (~15k chars), which pushes the Critic
     * prompt over Ollama's 4096-token context limit and causes silent truncation.
     * Critic is a decision evaluator — it needs the failure signal, not the full log.
     */
    public String getErrorSummary() {
        String full = getToolErrors();
        if (full == null || full.isEmpty()) return full;

        String[] lines = full.split("\n", -1);
        if (lines.length <= 15) return full;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("... (").append(lines.length - 15).append(" more lines omitted)");
        return sb.toString();
    }

    /**
     * Get summary of tool execution for logging.
     */
    public String getToolExecutionSummary() {
        return toolResults.stream()
                .map(r -> String.format("[%s: exitCode=%d]", r.getTool(), r.getExitCode()))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
    }

    /**
     * Check if tests passed.
     */
    public boolean testsPass() {
        return testResults != null && testResults.allPassed();
    }

    @Override
    public String toString() {
        return String.format(
            "ExecutionResult{task='%s', tools=%d, tests=%s, modified=%d}",
            taskDescription,
            toolResults.size(),
            testResults.getSummary(),
            modifiedFiles.size()
        );
    }
}