package com.synapsenet.communication;

import com.synapsenet.core.event.Event;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class InMemoryEventBus implements EventBus {

    private final List<SynapseEventListener> listeners =
            new CopyOnWriteArrayList<>();

    @Override
    public void publish(Event event) {
        for (SynapseEventListener listener : listeners) {
            listener.onEvent(event);
        }
    }

    @Override
    public void subscribe(SynapseEventListener listener) {
        listeners.add(listener);
    }
}
