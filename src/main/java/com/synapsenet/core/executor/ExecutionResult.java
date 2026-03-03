package com.synapsenet.core.executor;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExecutionResult - Result of executing a planner task.
 *
 * Contains:
 * - Task description
 * - Tool execution results
 * - Test results (if tests were run)
 * - List of modified files
 *
 * [Hardening] toolResults and modifiedFiles are defensively copied in the
 * primary constructor — callers cannot mutate internal state after construction.
 *
 * [Hardening] Constructor invariant: rawTestExitCode and testResults must be
 * internally consistent, enforced by validateConsistency() called from the
 * primary constructor. All other constructors and factories delegate to primary.
 */
public class ExecutionResult {

    private static final Logger log = LoggerFactory.getLogger(ExecutionResult.class);

    private final String taskDescription;
    private final List<ToolResult> toolResults;
    private final TestResults testResults;
    private final List<String> modifiedFiles;
    private final int rawTestExitCode;

    /**
     * Primary constructor.
     *
     * rawTestExitCode must be the actual process exit code from pytest:
     *   0  = all tests passed
     *   1  = at least one test failed (standard pytest)
     *   2  = collection error
     *  -1  = tests not run (use this value when testResults == TestResults.notRun())
     *
     * It must NOT be derived from passedCount or failedCount.
     *
     * [Hardening] toolResults and modifiedFiles are defensively copied so callers
     * cannot mutate internal state by modifying the lists they passed in.
     *
     * [Hardening] validateConsistency() enforces that rawTestExitCode and
     * testResults agree at construction time.
     */
    public ExecutionResult(
            String taskDescription,
            List<ToolResult> toolResults,
            TestResults testResults,
            List<String> modifiedFiles,
            int rawTestExitCode
    ) {
        // [Hardening] Defensive copy — never hold the caller's list reference.
        this.taskDescription  = taskDescription;
        this.toolResults      = toolResults   != null ? new ArrayList<>(toolResults)   : new ArrayList<>();
        this.testResults      = testResults   != null ? testResults                    : TestResults.notRun();
        this.modifiedFiles    = modifiedFiles != null ? new ArrayList<>(modifiedFiles) : new ArrayList<>();
        this.rawTestExitCode  = rawTestExitCode;
        log.info("[DEBUG] ExecutionResult rawTestExitCode = {}", rawTestExitCode);

        // [Hardening] Invariant: rawTestExitCode and testResults must be consistent.
        // All constructors and factories delegate here, so this check runs exactly once.
        validateConsistency(this.rawTestExitCode, this.testResults);
    }

    public ExecutionResult(
            String taskDescription,
            List<ToolResult> toolResults,
            TestResults testResults,
            List<String> modifiedFiles
    ) {
        this(taskDescription, toolResults, testResults, modifiedFiles, -1);
    }

    public static ExecutionResult error(String errorMessage) {
        ToolResult errorResult = ToolResult.error(errorMessage);
        return new ExecutionResult(
            "ERROR: " + errorMessage,
            List.of(errorResult),
            TestResults.notRun(),
            new ArrayList<>()
        );
    }

    public static ExecutionResult errorWithTestResults(
            String errorMessage,
            TestResults testResults,
            int rawTestExitCode
    ) {
        ToolResult errorResult = ToolResult.error(errorMessage);
        return new ExecutionResult(
            "ERROR: " + errorMessage,
            List.of(errorResult),
            testResults,
            new ArrayList<>(),
            rawTestExitCode
        );
    }

    // =========================================================================
    // Invariant validation
    // =========================================================================

    /**
     * Enforce consistency between rawTestExitCode and testResults.
     *
     * Rules:
     *   rawTestExitCode == -1 → testResults.wereRun() must be false
     *   rawTestExitCode ==  0 → testResults.allPassed() must be true
     *   rawTestExitCode  >  0 → testResults.allPassed() must be false
     *
     * Called only from the primary constructor. All other constructors and static
     * factories delegate to primary, so the check runs exactly once per instance.
     * Throws IllegalStateException on violation — a split-brain state that would
     * silently corrupt FSM transition decisions if allowed to propagate.
     */
    private static void validateConsistency(int rawTestExitCode, TestResults testResults) {
        if (rawTestExitCode == -1) {
            if (testResults.wereRun()) {
                throw new IllegalStateException(
                    "ExecutionResult invariant violated: rawTestExitCode=-1 (tests not run) " +
                    "but testResults.wereRun()=true. " +
                    "Use the actual pytest process exit code, not -1, when tests ran.");
            }
        } else if (rawTestExitCode == 0) {
            if (!testResults.allPassed()) {
                throw new IllegalStateException(
                    "ExecutionResult invariant violated: rawTestExitCode=0 (all passed) " +
                    "but testResults.allPassed()=false. " +
                    "testResults must reflect the same outcome as the process exit code.");
            }
        } else {
            // rawTestExitCode > 0  (1 = test failure, 2 = collection error, etc.)
            if (testResults.allPassed()) {
                throw new IllegalStateException(
                    "ExecutionResult invariant violated: rawTestExitCode=" + rawTestExitCode +
                    " (failure) but testResults.allPassed()=true. " +
                    "testResults must reflect the same outcome as the process exit code.");
            }
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public String getTaskDescription() { return taskDescription; }
    public List<ToolResult> getToolResults() { return toolResults; }
    public TestResults getTestResults() { return testResults; }
    public List<String> getModifiedFiles() { return modifiedFiles; }

    public boolean hasToolErrors() {
        return toolResults.stream().anyMatch(r -> r.getExitCode() != 0);
    }

    public boolean hasErrors() { return hasToolErrors(); }

    public String getToolErrors() {
        return toolResults.stream()
                .filter(r -> r.getExitCode() != 0)
                .map(r -> r.getStderr() != null && !r.getStderr().isEmpty()
                        ? r.getStderr()
                        : r.getStdout())
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();
    }

    public String getErrorSummary() {
        String full = getToolErrors();
        if (full == null || full.isEmpty()) return full;
        String[] lines = full.split("\n", -1);
        if (lines.length <= 15) return full;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) sb.append(lines[i]).append("\n");
        sb.append("... (").append(lines.length - 15).append(" more lines omitted)");
        return sb.toString();
    }

    public String getToolExecutionSummary() {
        return toolResults.stream()
                .map(r -> String.format("[%s: exitCode=%d]", r.getTool(), r.getExitCode()))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
    }

    public boolean testsPass() { return testResults != null && testResults.allPassed(); }

    public int getTestExitCode() {
        log.info("[DEBUG] getTestExitCode() returning {}", rawTestExitCode);
        return rawTestExitCode;
    }

    @Override
    public String toString() {
        return String.format(
            "ExecutionResult{task='%s', tools=%d, tests=%s, modified=%d}",
            taskDescription, toolResults.size(), testResults.getSummary(), modifiedFiles.size()
        );
    }
}