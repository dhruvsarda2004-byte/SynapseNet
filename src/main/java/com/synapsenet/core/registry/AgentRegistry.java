package com.synapsenet.core.registry;

import com.synapsenet.core.agent.Agent;
import com.synapsenet.core.agent.AgentType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component  // @Component → Spring-managed object

public class AgentRegistry {

    private final Map<AgentType, List<Agent>> agentsByType;

    public AgentRegistry(List<Agent> agents) { // “If a constructor asks for a List<T>, inject all beans of type T.”
        this.agentsByType = agents.stream()
                .collect(Collectors.groupingBy(Agent::getAgentType));
    }

    public List<Agent> getAgentsByType(AgentType type) {
        return agentsByType.getOrDefault(type, Collections.emptyList());
    }

    public Collection<Agent> getAllAgents() { // take all the lists of agents stored in the map, merge them into one big list, and return it.
        return agentsByType.values()
                .stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }
}
