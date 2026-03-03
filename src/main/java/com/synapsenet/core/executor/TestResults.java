package com.synapsenet.core.executor;

import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced TestResults with failure type classification.
 *
 * This allows the repair system to react differently based on
 * what kind of failure occurred.
 *
 * [Hardening] passingTests and failingTests are defensively copied in all
 * constructors — callers cannot mutate internal state after construction.
 *
 * [Hardening] allPassed() is anchored to rawExitCode only:
 *   return wereRun && rawExitCode == 0
 * The prior implementation also required !passingTests.isEmpty(), which caused
 * false negatives when pytest exited 0 but test names were not parseable. The
 * process exit code is ground truth; the parsed lists are supplementary detail.
 *
 * [Hardening] Constructor invariant on all public constructors: rawExitCode
 * and failingTests must agree. Enforced via validateRun(), called from all
 * constructors where wereRun=true. The private notRun() constructor is exempt —
 * wereRun=false means exit code and failing list carry no meaning.
 */
public class TestResults {

    private final List<String> passingTests;
    private final List<String> failingTests;
    private final String rawOutput;
    private final boolean wereRun;
    private final TestFailureType failureType;
    private final String errorSnippet;
    private final int rawExitCode;

    public TestResults(
            List<String> passingTests,
            List<String> failingTests,
            String rawOutput,
            TestFailureType failureType,
            String errorSnippet,
            int rawExitCode
    ) {
        // [Hardening] Defensive copy — never hold the caller's list reference.
        this.passingTests = passingTests != null ? new ArrayList<>(passingTests) : new ArrayList<>();
        this.failingTests = failingTests != null ? new ArrayList<>(failingTests) : new ArrayList<>();
        this.rawOutput    = rawOutput    != null ? rawOutput                     : "";
        this.wereRun      = true;
        this.failureType  = failureType;
        this.errorSnippet = errorSnippet;
        this.rawExitCode  = rawExitCode;

        // [Hardening] Invariant: exitCode and failingTests must agree.
        validateRun(this.rawExitCode, this.failingTests);
    }

    // -------------------------------------------------------------------------
    // BACKWARD-COMPATIBLE five-arg constructor (no exitCode).
    // rawExitCode inferred from failingTests — preserves prior semantics for
    // callers outside the test-run path.
    // -------------------------------------------------------------------------
    public TestResults(
            List<String> passingTests,
            List<String> failingTests,
            String rawOutput,
            TestFailureType failureType,
            String errorSnippet
    ) {
        // [Hardening] Defensive copy — never hold the caller's list reference.
        this.passingTests = passingTests != null ? new ArrayList<>(passingTests) : new ArrayList<>();
        this.failingTests = failingTests != null ? new ArrayList<>(failingTests) : new ArrayList<>();
        this.rawOutput    = rawOutput    != null ? rawOutput                     : "";
        this.wereRun      = true;
        this.failureType  = failureType;
        this.errorSnippet = errorSnippet;
        // Infer from failingTests — by construction always consistent.
        this.rawExitCode  = (failingTests != null && !failingTests.isEmpty()) ? 1 : 0;

        // [Hardening] Invariant — consistent by inference, validated defensively.
        validateRun(this.rawExitCode, this.failingTests);
    }

    // -------------------------------------------------------------------------
    // LEGACY three-arg constructor — backward compatibility only.
    // -------------------------------------------------------------------------
    public TestResults(List<String> passingTests, List<String> failingTests, String rawOutput) {
        // [Hardening] Defensive copy — never hold the caller's list reference.
        this.passingTests = passingTests != null ? new ArrayList<>(passingTests) : new ArrayList<>();
        this.failingTests = failingTests != null ? new ArrayList<>(failingTests) : new ArrayList<>();
        this.rawOutput    = rawOutput    != null ? rawOutput                     : "";
        this.wereRun      = true;
        this.failureType  = TestFailureType.UNKNOWN;
        this.errorSnippet = null;
        // Infer from failingTests — by construction always consistent.
        this.rawExitCode  = (failingTests != null && !failingTests.isEmpty()) ? 1 : 0;

        // [Hardening] Invariant — consistent by inference, validated defensively.
        validateRun(this.rawExitCode, this.failingTests);
    }

    private TestResults(boolean wereRun) {
        this.passingTests = new ArrayList<>();
        this.failingTests = new ArrayList<>();
        this.rawOutput    = "";
        this.wereRun      = wereRun;
        this.failureType  = TestFailureType.NONE;
        this.errorSnippet = null;
        this.rawExitCode  = 0;
    }

    // -------------------------------------------------------------------------
    // Invariant validation
    // -------------------------------------------------------------------------

    /**
     * Enforce consistency between rawExitCode and failingTests.
     *
     * Rules (meaningful only when wereRun=true — the private notRun() constructor
     * does not call this):
     *   rawExitCode == 0 → failingTests must be empty
     *   rawExitCode  > 0 → failingTests must NOT be empty
     *
     * Throws IllegalStateException on violation — a split-brain state where the
     * process reported success but failing tests were recorded (or vice versa)
     * would corrupt allPassed() and downstream FSM signals.
     *
     * The > 0 rule has one intentional exception: collectionErrorFallback() always
     * injects a sentinel entry ("signal_corruption_fallback") before calling the
     * primary constructor, so it satisfies the constraint even when the real pytest
     * output could not be parsed.
     */
    private static void validateRun(int rawExitCode, List<String> failingTests) {
        if (rawExitCode == 0 && !failingTests.isEmpty()) {
            throw new IllegalStateException(
                "TestResults invariant violated: rawExitCode=0 (success) " +
                "but failingTests is non-empty: " + failingTests + ". " +
                "A process exit of 0 means all tests passed — no failing tests may be recorded.");
        }
        if (rawExitCode > 0 && failingTests.isEmpty()) {
            throw new IllegalStateException(
                "TestResults invariant violated: rawExitCode=" + rawExitCode +
                " (failure) but failingTests is empty. " +
                "A non-zero exit code requires at least one failing test to be recorded. " +
                "Use collectionErrorFallback() if pytest output could not be parsed.");
        }
    }

    // -------------------------------------------------------------------------
    // Factories
    // -------------------------------------------------------------------------

    public static TestResults notRun() { return new TestResults(false); }

    public static TestResults failed(String reason) {
        List<String> failing = new ArrayList<>();
        failing.add("generic_failure");
        return new TestResults(new ArrayList<>(), failing, reason, TestFailureType.UNKNOWN, reason, 1);
    }

    public static TestResults allPassed(int count) {
        List<String> passing = new ArrayList<>();
        for (int i = 0; i < count; i++) passing.add("test_" + i);
        return new TestResults(passing, new ArrayList<>(), "", TestFailureType.NONE, null, 0);
    }

    public static TestResults collectionErrorFallback(String rawOutput, int exitCode) {
        List<String> failing = new ArrayList<>();
        failing.add("signal_corruption_fallback");
        return new TestResults(
            new ArrayList<>(), failing,
            rawOutput != null ? rawOutput : "",
            TestFailureType.COLLECTION_ERROR,
            "Signal corruption: could not parse failing test names from pytest output",
            exitCode
        );
    }

    public List<String> getPassingTests()    { return new ArrayList<>(passingTests); }
    public List<String> getFailingTests()    { return new ArrayList<>(failingTests); }
    public String       getRawOutput()       { return rawOutput; }
    public boolean      wereRun()            { return wereRun; }
    public boolean      anyFailed()          { return !failingTests.isEmpty(); }
    public TestFailureType getFailureType()  { return failureType; }
    public String       getErrorSnippet()    { return errorSnippet; }

    /**
     * Returns true when tests ran AND the process exited cleanly (rawExitCode == 0).
     *
     * [Hardening] Anchored to rawExitCode only — the process exit code is ground truth.
     * The prior implementation also required !passingTests.isEmpty(), which caused
     * false negatives when pytest exited 0 but no test names were parseable from the
     * output (e.g. minimal output format). The constructor invariant already guarantees
     * that rawExitCode == 0 → failingTests is empty, making the extra checks both
     * redundant and hazardous.
     */
    public boolean allPassed() {
        return wereRun && rawExitCode == 0;
    }

    public String getSummary() {
        if (!wereRun) return "Tests not run";
        int passed = passingTests.size();
        int failed = failingTests.size();
        if (failed == 0) return passed + " passed, 0 failed";
        String summary = passed + " passed, " + failed + " failed";
        if (failureType != TestFailureType.NONE && failureType != TestFailureType.UNKNOWN)
            summary += " (" + failureType + ")";
        return summary;
    }

    public String getDetailedFailureSummary() {
        if (!anyFailed()) return "All tests passed";
        StringBuilder sb = new StringBuilder();
        sb.append("Failure Type: ").append(failureType).append("\n");
        sb.append("Failed Tests: ").append(failingTests.size()).append("\n");
        for (String test : failingTests) sb.append("  - ").append(test).append("\n");
        if (errorSnippet != null && !errorSnippet.isEmpty())
            sb.append("\nKey Error:\n").append(errorSnippet).append("\n");
        return sb.toString();
    }

    public String getRepairHint() {
        return switch (failureType) {
            case ASSERTION_ERROR  -> "The logic is incorrect. Review the algorithm and fix the computation.";
            case SYNTAX_ERROR     -> "Python syntax is broken. Check indentation, brackets, colons, and structure.";
            case IMPORT_ERROR     -> "Missing import or module. Add the required import statement.";
            case ATTRIBUTE_ERROR  -> "Wrong method or property name. Check the object type and available methods.";
            case TYPE_ERROR       -> "Type mismatch in operation. Ensure compatible types or add type conversion.";
            case INDEX_ERROR      -> "List/array index out of bounds. Add bounds checking or fix indexing logic.";
            case KEY_ERROR        -> "Dictionary key doesn't exist. Use .get() with default or check key existence.";
            case COLLECTION_ERROR -> "Pytest couldn't collect tests. Fix structural issues preventing test discovery.";
            case UNKNOWN          -> "Unclassified error. Analyze the error message carefully.";
            case NONE             -> "No error - tests passed.";
        };
    }

    @Override
    public String toString() { return getSummary(); }
}