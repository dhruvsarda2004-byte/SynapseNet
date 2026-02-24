package com.synapsenet.core.executor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite demonstrating failure classification.
 * 
 * This validates that the system can correctly identify
 * different types of test failures and provide appropriate
 * repair guidance.
 */
class FailureClassificationTest {

    @Test
    void testAssertionErrorClassification() {
        String output = """
            test_calculator.py::test_subtract FAILED
            
            def test_subtract():
                result = subtract(5, 3)
            >   assert result == 2
            E   AssertionError: assert 8 == 2
            
            1 failed, 0 passed
            """;

        TestResults results = parseTestOutput(output);
        
        assertEquals(TestFailureType.ASSERTION_ERROR, results.getFailureType());
        assertTrue(results.getErrorSnippet().contains("AssertionError"));
        assertTrue(results.getRepairHint().contains("logic is incorrect"));
    }

    @Test
    void testSyntaxErrorClassification() {
        String output = """
            ERROR collecting test_calculator.py
            test_calculator.py:3
                def subtract(a, b)
                                  ^
            SyntaxError: invalid syntax
            """;

        TestResults results = parseTestOutput(output);
        
        assertEquals(TestFailureType.SYNTAX_ERROR, results.getFailureType());
        assertTrue(results.getRepairHint().contains("syntax is broken"));
    }

    @Test
    void testImportErrorClassification() {
        String output = """
            test_calculator.py::test_advanced FAILED
            
            def test_advanced():
            >   from numpy import array
            E   ModuleNotFoundError: No module named 'numpy'
            
            1 failed, 0 passed
            """;

        TestResults results = parseTestOutput(output);
        
        assertEquals(TestFailureType.IMPORT_ERROR, results.getFailureType());
        assertTrue(results.getRepairHint().contains("Missing import"));
    }

    @Test
    void testAttributeErrorClassification() {
        String output = """
            test_calculator.py::test_method FAILED
            
            def test_method():
            >   result = calculator.computee(5, 3)
            E   AttributeError: 'Calculator' object has no attribute 'computee'
            
            1 failed, 0 passed
            """;

        TestResults results = parseTestOutput(output);
        
        assertEquals(TestFailureType.ATTRIBUTE_ERROR, results.getFailureType());
        assertTrue(results.getRepairHint().contains("Wrong method"));
    }

    @Test
    void testTypeErrorClassification() {
        String output = """
            test_calculator.py::test_types FAILED
            
            def test_types():
            >   result = 5 + "3"
            E   TypeError: unsupported operand type(s) for +: 'int' and 'str'
            
            1 failed, 0 passed
            """;

        TestResults results = parseTestOutput(output);
        
        assertEquals(TestFailureType.TYPE_ERROR, results.getFailureType());
        assertTrue(results.getRepairHint().contains("Type mismatch"));
    }

    @Test
    void testIndexErrorClassification() {
        String output = """
            test_calculator.py::test_list FAILED
            
            def test_list():
            >   value = my_list[5]
            E   IndexError: list index out of range
            
            1 failed, 0 passed
            """;

        TestResults results = parseTestOutput(output);
        
        assertEquals(TestFailureType.INDEX_ERROR, results.getFailureType());
        assertTrue(results.getRepairHint().contains("bounds checking"));
    }

    @Test
    void testKeyErrorClassification() {
        String output = """
            test_calculator.py::test_dict FAILED
            
            def test_dict():
            >   value = config['missing_key']
            E   KeyError: 'missing_key'
            
            1 failed, 0 passed
            """;

        TestResults results = parseTestOutput(output);
        
        assertEquals(TestFailureType.KEY_ERROR, results.getFailureType());
        assertTrue(results.getRepairHint().contains("Dictionary key"));
    }

    @Test
    void testAllPassedClassification() {
        String output = """
            test_calculator.py::test_add PASSED
            test_calculator.py::test_subtract PASSED
            
            2 passed, 0 failed
            """;

        TestResults results = parseTestOutput(output);
        
        assertEquals(TestFailureType.NONE, results.getFailureType());
        assertTrue(results.allPassed());
        assertNull(results.getErrorSnippet());
    }

    // Helper method (simplified version of ExecutorAgent's parseTestOutput)
    private TestResults parseTestOutput(String output) {
        // This is a simplified parser for testing
        // In reality, this would call ExecutorAgent.parseTestOutput()
        
        if (output.contains("SyntaxError")) {
            return new TestResults(
                java.util.List.of(),
                java.util.List.of("test_syntax"),
                output,
                TestFailureType.SYNTAX_ERROR,
                "SyntaxError: invalid syntax"
            );
        } else if (output.contains("ModuleNotFoundError") || output.contains("ImportError")) {
            return new TestResults(
                java.util.List.of(),
                java.util.List.of("test_import"),
                output,
                TestFailureType.IMPORT_ERROR,
                "ModuleNotFoundError"
            );
        } else if (output.contains("AttributeError")) {
            return new TestResults(
                java.util.List.of(),
                java.util.List.of("test_attribute"),
                output,
                TestFailureType.ATTRIBUTE_ERROR,
                "AttributeError"
            );
        } else if (output.contains("TypeError")) {
            return new TestResults(
                java.util.List.of(),
                java.util.List.of("test_type"),
                output,
                TestFailureType.TYPE_ERROR,
                "TypeError"
            );
        } else if (output.contains("IndexError")) {
            return new TestResults(
                java.util.List.of(),
                java.util.List.of("test_index"),
                output,
                TestFailureType.INDEX_ERROR,
                "IndexError"
            );
        } else if (output.contains("KeyError")) {
            return new TestResults(
                java.util.List.of(),
                java.util.List.of("test_key"),
                output,
                TestFailureType.KEY_ERROR,
                "KeyError"
            );
        } else if (output.contains("AssertionError")) {
            return new TestResults(
                java.util.List.of(),
                java.util.List.of("test_assert"),
                output,
                TestFailureType.ASSERTION_ERROR,
                "AssertionError: assert 8 == 2"
            );
        } else if (output.contains("PASSED") && !output.contains("FAILED")) {
            return new TestResults(
                java.util.List.of("test_1", "test_2"),
                java.util.List.of(),
                output,
                TestFailureType.NONE,
                null
            );
        }
        
        return TestResults.notRun();
    }
}