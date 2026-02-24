package com.synapsenet.core.executor;

import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced TestResults with failure type classification.
 * 
 * This allows the repair system to react differently based on
 * what kind of failure occurred.
 */
public class TestResults {

    private final List<String> passingTests;
    private final List<String> failingTests;
    private final String rawOutput;
    private final boolean wereRun;
    private final TestFailureType failureType;
    private final String errorSnippet; // Key error message

    public TestResults(
            List<String> passingTests,
            List<String> failingTests,
            String rawOutput,
            TestFailureType failureType,
            String errorSnippet
    ) {
        this.passingTests = passingTests != null ? passingTests : new ArrayList<>();
        this.failingTests = failingTests != null ? failingTests : new ArrayList<>();
        this.rawOutput = rawOutput != null ? rawOutput : "";
        this.wereRun = true;
        this.failureType = failureType;
        this.errorSnippet = errorSnippet;
    }

    // Legacy constructor for backward compatibility
    public TestResults(List<String> passingTests, List<String> failingTests, String rawOutput) {
        this(passingTests, failingTests, rawOutput, TestFailureType.UNKNOWN, null);
    }

    private TestResults(boolean wereRun) {
        this.passingTests = new ArrayList<>();
        this.failingTests = new ArrayList<>();
        this.rawOutput = "";
        this.wereRun = wereRun;
        this.failureType = TestFailureType.NONE;
        this.errorSnippet = null;
    }

    /**
     * Factory: Tests were not run
     */
    public static TestResults notRun() {
        return new TestResults(false);
    }

    /**
     * Factory: Tests failed with generic message
     */
    public static TestResults failed(String reason) {
        List<String> failing = new ArrayList<>();
        failing.add("generic_failure");
        return new TestResults(new ArrayList<>(), failing, reason, TestFailureType.UNKNOWN, reason);
    }

    /**
     * Factory: All tests passed
     */
    public static TestResults allPassed(int count) {
        List<String> passing = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            passing.add("test_" + i);
        }
        return new TestResults(passing, new ArrayList<>(), "", TestFailureType.NONE, null);
    }

    // Getters
    public List<String> getPassingTests() {
        return new ArrayList<>(passingTests);
    }

    public List<String> getFailingTests() {
        return new ArrayList<>(failingTests);
    }

    public String getRawOutput() {
        return rawOutput;
    }

    public boolean wereRun() {
        return wereRun;
    }

    public boolean anyFailed() {
        return !failingTests.isEmpty();
    }

    public boolean allPassed() {
        return wereRun && failingTests.isEmpty() && !passingTests.isEmpty();
    }

    public TestFailureType getFailureType() {
        return failureType;
    }

    public String getErrorSnippet() {
        return errorSnippet;
    }

    /**
     * Get human-readable summary
     */
    public String getSummary() {
        if (!wereRun) {
            return "Tests not run";
        }
        
        int passed = passingTests.size();
        int failed = failingTests.size();
        
        if (failed == 0) {
            return passed + " passed, 0 failed";
        }
        
        String summary = passed + " passed, " + failed + " failed";
        
        if (failureType != TestFailureType.NONE && failureType != TestFailureType.UNKNOWN) {
            summary += " (" + failureType + ")";
        }
        
        return summary;
    }

    /**
     * Get detailed failure summary for LLM
     */
    public String getDetailedFailureSummary() {
        if (!anyFailed()) {
            return "All tests passed";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Failure Type: ").append(failureType).append("\n");
        sb.append("Failed Tests: ").append(failingTests.size()).append("\n");
        
        for (String test : failingTests) {
            sb.append("  - ").append(test).append("\n");
        }
        
        if (errorSnippet != null && !errorSnippet.isEmpty()) {
            sb.append("\nKey Error:\n");
            sb.append(errorSnippet).append("\n");
        }
        
        return sb.toString();
    }

    /**
     * Get repair strategy hint based on failure type
     */
    public String getRepairHint() {
        return switch (failureType) {
            case ASSERTION_ERROR -> 
                "The logic is incorrect. Review the algorithm and fix the computation.";
            case SYNTAX_ERROR -> 
                "Python syntax is broken. Check indentation, brackets, colons, and structure.";
            case IMPORT_ERROR -> 
                "Missing import or module. Add the required import statement.";
            case ATTRIBUTE_ERROR -> 
                "Wrong method or property name. Check the object type and available methods.";
            case TYPE_ERROR -> 
                "Type mismatch in operation. Ensure compatible types or add type conversion.";
            case INDEX_ERROR -> 
                "List/array index out of bounds. Add bounds checking or fix indexing logic.";
            case KEY_ERROR -> 
                "Dictionary key doesn't exist. Use .get() with default or check key existence.";
            case COLLECTION_ERROR -> 
                "Pytest couldn't collect tests. Fix structural issues preventing test discovery.";
            case UNKNOWN -> 
                "Unclassified error. Analyze the error message carefully.";
            case NONE -> 
                "No error - tests passed.";
        };
    }

    @Override
    public String toString() {
        return getSummary();
    }
}