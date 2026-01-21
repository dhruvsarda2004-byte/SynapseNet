package com.synapsenet.core.agent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class ThinkingCompletionChecker {

    private static final Set<AgentType> REQUIRED_AGENT_TYPES =
            Set.of(
                AgentType.PLANNER,
                AgentType.EXECUTOR,
                AgentType.CRITIC
            );

    public boolean isThinkingComplete(List<AgentResult> results) {

        Set<AgentType> presentAgentTypes = results.stream()
                .map(AgentResult::getAgentType)
                .collect(Collectors.toSet());

        return presentAgentTypes.containsAll(REQUIRED_AGENT_TYPES);
    }
}
