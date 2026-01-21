package com.synapsenet.orchestrator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.communication.SynapseEventListener;
import com.synapsenet.core.agent.AgentResult;
import com.synapsenet.core.agent.AgentResultStore;
import com.synapsenet.core.agent.ThinkingCompletionChecker;
import com.synapsenet.core.event.Event;
import com.synapsenet.core.event.EventType;

@Component
public class SimpleTaskOrchestrator implements SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(SimpleTaskOrchestrator.class);

    private final AgentResultStore resultStore;
    private final ThinkingCompletionChecker completionChecker;
    private final FinalAnswerAssembler finalAnswerAssembler;

    // DAY 11: prevent duplicate final answers
    private final Set<String> completedTasks = new HashSet<>();

    public SimpleTaskOrchestrator(
            AgentResultStore resultStore,
            ThinkingCompletionChecker completionChecker,
            FinalAnswerAssembler finalAnswerAssembler) {
        this.resultStore = resultStore;
        this.completionChecker = completionChecker; 
        this.finalAnswerAssembler = finalAnswerAssembler;
    }

    @Override
    public void onEvent(Event event) {

        if (event.getType() == EventType.AGENT_RESULT_PRODUCED) {

            AgentResult result = (AgentResult) event.getPayload();
            String taskId = result.getTaskId();

            // DAY 11: ignore if already finalized
            if (completedTasks.contains(taskId)) {
                return;
            }

            List<AgentResult> results =
                    resultStore.getResultsForTask(taskId);

            if (completionChecker.isThinkingComplete(results)) {

                completedTasks.add(taskId);
                log.info("Thinking complete for task {}", taskId);

                finalAnswerAssembler.assemble(taskId, results);

            }
        }
    }
}
