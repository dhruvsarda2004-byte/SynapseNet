package com.synapsenet.llm;

import com.synapsenet.core.agent.AgentType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
public class MockLLMClient implements LLMClient {

    @Override
    public String generateWithRole(AgentType role, String userPrompt, double temperature) {
        // Stub: return a minimal valid repair plan JSON for all roles
        return """
                {
                  "repair_steps": [
                    "Step 1: Analyze the task",
                    "Step 2: Break it into sub-tasks",
                    "Step 3: Execute step by step"
                  ],
                  "reasoning": "Mock plan"
                }
                """;
    }
}