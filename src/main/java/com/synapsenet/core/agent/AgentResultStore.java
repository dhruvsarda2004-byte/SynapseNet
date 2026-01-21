package com.synapsenet.core.agent;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class AgentResultStore {   // central in-memory repository for agent outputs

    private final Map<String, List<AgentResult>> resultsByTask = new HashMap<>();

    public void addResult(AgentResult result) {
        resultsByTask
                .computeIfAbsent(result.getTaskId(), k -> new ArrayList<>())
                .add(result);
    }

    public List<AgentResult> getResultsForTask(String taskId) {
        return resultsByTask.getOrDefault(taskId, Collections.emptyList());
    }
}
