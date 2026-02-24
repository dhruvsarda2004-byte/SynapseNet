package com.synapsenet.orchestrator;

import com.synapsenet.orchestrator.SimpleTaskOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("ollama")
class RealMiniRepoTest {

    @Autowired
    private SimpleTaskOrchestrator orchestrator;

    @Test
    void runFullRepositoryRepair() {
        orchestrator.runTask(
            "Run pytest on the entire repository and repair any failing tests."
        );
    }
}