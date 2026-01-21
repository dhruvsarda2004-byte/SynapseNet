package com.synapsenet.core.event;

import java.time.Instant;
import java.util.UUID;

public class Event {

    private final String eventId;
    private final EventType type;
    private final String source;
    private final Object payload;
    
    // payload means extra data related to this event 
    // why is payload of type object ? :
    // because different events can carry diff payloads, using object makes it generic else we would have to make a lot of different event classes 
    
    private final Instant timestamp;

    public Event(EventType type, String source, Object payload) {
        this.eventId = UUID.randomUUID().toString();
        this.type = type;
        this.source = source;
        this.payload = payload;
        this.timestamp = Instant.now();
    }

    public String getEventId() {
        return eventId;
    }

    public EventType getType() {
        return type;
    }

    public String getSource() {
        return source;
    }

    public Object getPayload() {
        return payload;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
