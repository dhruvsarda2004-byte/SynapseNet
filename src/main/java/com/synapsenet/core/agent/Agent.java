package com.synapsenet.core.agent;

public interface Agent {

    String getAgentId();

    AgentType getAgentType();

    void handleTask(String taskId);
}
