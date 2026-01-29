package com.synapsenet.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExecutionModeResolver {

    private final ExecutionMode mode;

    public ExecutionModeResolver(
        @Value("${synapsenet.execution-mode}") String mode
    ) {
        this.mode = ExecutionMode.valueOf(mode.toUpperCase());
    }

    public boolean isCIR() {
        return mode == ExecutionMode.CIR;
    }

    public boolean isEIR() {
        return mode == ExecutionMode.EIR;
    }

    public ExecutionMode getMode() {
        return mode;
    }
}
