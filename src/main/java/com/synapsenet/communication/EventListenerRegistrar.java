package com.synapsenet.communication;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class EventListenerRegistrar {

    private final EventBus eventBus;
    private final List<SynapseEventListener> listeners;

    public EventListenerRegistrar(    // constructor 
            EventBus eventBus,
            List<SynapseEventListener> listeners) {
        this.eventBus = eventBus;
        this.listeners = listeners;
    }

    @EventListener(ApplicationReadyEvent.class) 
    public void registerListeners() {
        for (SynapseEventListener listener : listeners) {
            eventBus.subscribe(listener);
        }
    }
}