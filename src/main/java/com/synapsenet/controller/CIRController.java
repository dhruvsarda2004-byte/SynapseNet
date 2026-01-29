package com.synapsenet.controller;

import com.synapsenet.orchestrator.SimpleTaskOrchestrator;
import com.synapsenet.orchestrator.dto.CIRResult;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/cir")
public class CIRController {

    private final SimpleTaskOrchestrator orchestrator;

    public CIRController(SimpleTaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/run")
    public ResponseEntity<CIRResult> runCIR(
            @RequestBody Map<String, String> request
    ) {

        String task = request.get("task");

        if (task == null || task.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        CIRResult result = orchestrator.runTask(task);

        return ResponseEntity.ok(result);
    }
}
