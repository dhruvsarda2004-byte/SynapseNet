package com.synapsenet.core.agent;

public class AgentResult {  // DTO 

    private final String taskId;
    private final AgentType agentType;
    private final String agentName;
    private final String content;

    public AgentResult(String taskId,
                       AgentType agentType,
                       String agentName,
                       String content) {
        this.taskId = taskId;
        this.agentType = agentType;
        this.agentName = agentName;
        this.content = content;
    }

    public String getTaskId() {
        return taskId;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getContent() {
        return content;
    }
}
