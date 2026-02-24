package com.synapsenet.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class SimpleTaskOrchestratorTest {

    @Autowired
    private SimpleTaskOrchestrator orchestrator;

    @Test
    void testBasicLoop() {

        String goal = "Fix bug in calculator";

        Map<String, Object> result = orchestrator.runTask(goal);

        assertNotNull(result);
        assertNotNull(result.get("success"));
        assertNotNull(result.get("totalIterations"));
        assertNotNull(result.get("status"));

        int iterations = (Integer) result.get("totalIterations");
        assertTrue(iterations > 0, "Should run at least one iteration");
        assertTrue(iterations <= 20, "Should not exceed iteration cap");

        System.out.println("Loop completed in: " + iterations + " iterations");
        System.out.println("Status: "  + result.get("status"));
        System.out.println("Success: " + result.get("success"));
    }
}