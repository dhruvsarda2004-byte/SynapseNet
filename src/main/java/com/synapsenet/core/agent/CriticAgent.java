package com.synapsenet.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.synapsenet.communication.EventBus;
import com.synapsenet.communication.SynapseEventListener;
import com.synapsenet.core.event.Event;
import com.synapsenet.core.event.EventType;

@Component
public class CriticAgent implements Agent, SynapseEventListener {

    private static final Logger log =
            LoggerFactory.getLogger(CriticAgent.class);

    private final EventBus eventBus;

    public CriticAgent(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public String getAgentId() {
        return "critic-agent-1";
    }

    @Override
    public AgentType getAgentType() {
        return AgentType.CRITIC;
    }

    @Override
    public void handleTask(String taskId) {
        log.info("[CriticAgent] Reviewing completed task {}", taskId);

        // DAY 7 ADDITION: produce result
        AgentResult result = new AgentResult(    // new agent result is created 
                taskId,
                getAgentType(),
                getAgentId(),
                "Task reviewed; no critical issues found"
        );

        eventBus.publish(new Event(   // an event is published of the same 
                EventType.AGENT_RESULT_PRODUCED,
                getAgentId(),
                result
        ));
    }

    
    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.TASK_COMPLETED) {
            String taskId = event.getPayload().toString();
            handleTask(taskId);
        }
    }
}
