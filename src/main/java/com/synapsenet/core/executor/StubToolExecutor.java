package com.synapsenet.core.executor;

import com.synapsenet.core.agent.ExecutorAgent;
import com.synapsenet.core.state.SharedState;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class StubToolExecutor implements ToolExecutor {

    @Override
    public ToolResult execute(ExecutorAgent.ToolCall call) {
        return execute(call, null);
    }

    @Override
    public ToolResult execute(ExecutorAgent.ToolCall call, SharedState state) {
        // ToolResult constructor: (String tool, int exitCode, String stdout, String stderr,
        //                          String targetFile, String testTarget)
        if ("run_tests".equals(call.getTool())) {
            return new ToolResult(
                    call.getTool(),
                    0,
                    "3 passed, 0 failed",
                    "",
                    null,
                    null
            );
        }
        return new ToolResult(
                call.getTool(),
                0,
                "Stub execution success",
                "",
                null,
                null
        );
    }
}