package com.synapsenet.communication;

import com.synapsenet.core.event.Event;

public interface EventBus {

    void publish(Event event);

    void subscribe(SynapseEventListener listener);
}
