package com.synapsenet.core.executor;

public class ToolCall {

    private final String tool;
    private final String args;

    public ToolCall(String tool, String args) {
        this.tool = tool;
        this.args = args;
    }

    public String getTool() {
        return tool;
    }

    public String getArgs() {
        return args;
    }
}
