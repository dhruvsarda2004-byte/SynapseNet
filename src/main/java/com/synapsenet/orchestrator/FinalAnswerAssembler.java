package com.synapsenet.orchestrator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.core.agent.AgentResult;
import com.synapsenet.core.agent.AgentType;

@Component
public class FinalAnswerAssembler {

    private static final Logger log =
            LoggerFactory.getLogger(FinalAnswerAssembler.class);

    public FinalAnswer assemble(
            String taskId,
            List<AgentResult> results) {

        Map<AgentType, String> contributions = new HashMap<>();

        for (AgentResult result : results) {
            contributions.put(
                    result.getAgentType(),
                    result.getContent()
            );
        }

        FinalAnswer answer =
                new FinalAnswer(taskId, contributions);

        // Temporary: still print for visibility
        print(answer);

        return answer;
    }

    private void print(FinalAnswer answer) {

        log.info("=================================================");
        log.info("FINAL ANSWER FOR TASK {}", answer.getTaskId());
        log.info("-------------------------------------------------");

        answer.getAgentContributions()
                .forEach((type, content) ->
                        log.info("[{}] {}", type, content));

        log.info("=================================================");
    }
}
