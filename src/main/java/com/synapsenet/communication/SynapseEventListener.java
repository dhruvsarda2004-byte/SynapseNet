package com.synapsenet.communication;

import com.synapsenet.core.event.Event;

public interface SynapseEventListener  {

    void onEvent(Event event);
}
