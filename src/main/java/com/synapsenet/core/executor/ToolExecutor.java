package com.synapsenet.core.executor;

import com.synapsenet.core.agent.ExecutorAgent;
import com.synapsenet.core.state.SharedState;

/**
 * ToolExecutor Interface
 * 
 * Supports both legacy (without state) and new (with state) execution patterns.
 */
public interface ToolExecutor {
    
    /**
     * Legacy method - executes tool without state tracking
     * Used when file caching is not needed
     */
    ToolResult execute(ExecutorAgent.ToolCall call);
    
    /**
     * New method - executes tool with state tracking
     * Enables file caching for hybrid prompt system
     * 
     * @param call The tool call to execute
     * @param state SharedState for caching file reads (can be null)
     * @return ToolResult containing execution output
     */
    ToolResult execute(ExecutorAgent.ToolCall call, SharedState state);
}