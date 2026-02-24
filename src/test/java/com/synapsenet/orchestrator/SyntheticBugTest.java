package com.synapsenet.orchestrator;

import com.synapsenet.core.filesystem.FileSystemManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SyntheticBugTest — integration tests against local synthetic bug workspaces.
 *
 * WORKSPACE CONFIGURATION:
 *   Set synapsenet.workspace.path in application-ollama.properties (or via env var)
 *   to point at the bug directory before running each test.
 *
 *   FileSystemManager.workspace is FINAL — it cannot be changed at runtime.
 *   Each bug requires either:
 *     a) A separate application-bugXXX.properties profile, OR
 *     b) Running each test individually with the workspace path set accordingly.
 *
 * NOTE: @ActiveProfiles("ollama") — these tests require a running Ollama instance.
 *   Run with:  mvn test -Dtest=SyntheticBugTest -Dspring.profiles.active=ollama
 */
@SpringBootTest
@ActiveProfiles("ollama")
public class SyntheticBugTest {

    @Autowired
    private SimpleTaskOrchestrator orchestrator;

    @Autowired
    private FileSystemManager fsManager;

    @Value("${synapsenet.workspace.path}")
    private String workspacePath;

    /**
     * Bug 001: Wrong Operator
     * multiply() uses division instead of multiplication.
     * Workspace must be set to: synthetic-bugs/bug-001-wrong-operator
     */
    @Test
    public void testBug001_WrongOperator() {
        System.out.println("\n========== BUG 001: WRONG OPERATOR ==========");
        System.out.println("Workspace: " + workspacePath);

        Map<String, Object> result = orchestrator.runTask(
                "Fix the bug in calculator.py - the multiply function is broken");

        printResult("BUG 001", result);

        assertTrue(
            Boolean.TRUE.equals(result.get("success"))
                || String.valueOf(result.get("status")).toLowerCase().contains("pass"),
            "Bug 001 should be fixed successfully. Status: " + result.get("status")
        );
    }

    /**
     * Bug 002: Missing Function
     * multiply() function doesn't exist at all.
     * Workspace must be set to: synthetic-bugs/bug-002-missing-function
     */
    @Test
    public void testBug002_MissingFunction() {
        System.out.println("\n========== BUG 002: MISSING FUNCTION ==========");
        System.out.println("Workspace: " + workspacePath);

        Map<String, Object> result = orchestrator.runTask(
                "Fix the bug in math_utils.py - a function is missing");

        printResult("BUG 002", result);

        assertTrue(
            Boolean.TRUE.equals(result.get("success"))
                || String.valueOf(result.get("status")).toLowerCase().contains("pass"),
            "Bug 002 should be fixed successfully. Status: " + result.get("status")
        );
    }

    /**
     * Bug 003: Type Error
     * concat() has hardcoded integer instead of parameter.
     * Workspace must be set to: synthetic-bugs/bug-003-type-error
     */
    @Test
    public void testBug003_TypeError() {
        System.out.println("\n========== BUG 003: TYPE ERROR ==========");
        System.out.println("Workspace: " + workspacePath);

        Map<String, Object> result = orchestrator.runTask(
                "Fix the bug in string_utils.py - there's a type error");

        printResult("BUG 003", result);

        assertTrue(
            Boolean.TRUE.equals(result.get("success"))
                || String.valueOf(result.get("status")).toLowerCase().contains("pass"),
            "Bug 003 should be fixed successfully. Status: " + result.get("status")
        );
    }

    // =========================================================================

    private void printResult(String label, Map<String, Object> result) {
        System.out.println("\n=== " + label + " RESULTS ===");
        System.out.println("Success:    " + result.get("success"));
        System.out.println("Iterations: " + result.get("totalIterations"));
        System.out.println("Status:     " + result.get("status"));
        System.out.println("Details:    " + result.get("details"));
        System.out.println("=".repeat(30));
    }
}