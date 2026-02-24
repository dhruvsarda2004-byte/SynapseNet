package com.synapsenet.core.executor;

/**
 * Classification of test failure types.
 * 
 * Different failure types require different repair strategies:
 * - ASSERTION_ERROR: Logic is wrong, need to fix algorithm
 * - SYNTAX_ERROR: Patch broke Python syntax, need to fix structure
 * - IMPORT_ERROR: Missing dependency, need to add import
 * - ATTRIBUTE_ERROR: Wrong method/property access
 * - TYPE_ERROR: Type mismatch in operation
 * - INDEX_ERROR: List/array access out of bounds
 * - KEY_ERROR: Dictionary key doesn't exist
 * - COLLECTION_ERROR: Pytest couldn't collect tests (structural issue)
 * - UNKNOWN: Other or unclassified error
 */
public enum TestFailureType {
    /**
     * Test assertion failed - logic is incorrect
     * Example: assert result == 5 (but got 3)
     */
    ASSERTION_ERROR,
    
    /**
     * Python syntax error - patch broke code structure
     * Example: invalid indentation, missing colon, unclosed bracket
     */
    SYNTAX_ERROR,
    
    /**
     * Import failed - missing module or dependency
     * Example: ModuleNotFoundError, ImportError
     */
    IMPORT_ERROR,
    
    /**
     * Attribute doesn't exist - wrong method/property name
     * Example: AttributeError: 'list' object has no attribute 'append_all'
     */
    ATTRIBUTE_ERROR,
    
    /**
     * Type mismatch - wrong type in operation
     * Example: TypeError: unsupported operand type(s) for +: 'int' and 'str'
     */
    TYPE_ERROR,
    
    /**
     * Index out of bounds - list/array access error
     * Example: IndexError: list index out of range
     */
    INDEX_ERROR,
    
    /**
     * Dictionary key missing
     * Example: KeyError: 'username'
     */
    KEY_ERROR,
    
    /**
     * Pytest collection failed - can't run tests at all
     * Example: syntax error prevents test discovery
     */
    COLLECTION_ERROR,
    
    /**
     * Unknown or unclassified error
     */
    UNKNOWN,
    
    /**
     * Tests passed - no failure
     */
    NONE
}