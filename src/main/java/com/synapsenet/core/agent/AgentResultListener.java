package com.synapsenet.core.agent;

import org.springframework.stereotype.Component;

import com.synapsenet.communication.SynapseEventListener;
import com.synapsenet.core.event.Event;
import com.synapsenet.core.event.EventType;

@Component
public class AgentResultListener implements SynapseEventListener {  // its job is to store the result in store 

    private final AgentResultStore store;

    public AgentResultListener(AgentResultStore store) {
        this.store = store;
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.AGENT_RESULT_PRODUCED) {
            AgentResult result = (AgentResult) event.getPayload();  // payload has the data which needs to be stored in store 
            store.addResult(result);
        }
    }
}
