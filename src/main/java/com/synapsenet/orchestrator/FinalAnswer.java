package com.synapsenet.orchestrator;

import java.util.Map;

import com.synapsenet.core.agent.AgentType;

public class FinalAnswer {

    private final String taskId;
    private final Map<AgentType, String> agentContributions;

    public FinalAnswer(
            String taskId,
            Map<AgentType, String> agentContributions) {
        this.taskId = taskId;
        this.agentContributions = agentContributions;
    }

    public String getTaskId() {
        return taskId;
    }

    public Map<AgentType, String> getAgentContributions() {
        return agentContributions;
    }
}
