package com.synapsenet.core.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PythonExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonExecutor.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int PYTEST_TIMEOUT_SECONDS = 60;

    private final Path workingDirectory;
    private final String pythonInterpreter;

    public PythonExecutor(
            @Value("${synapsenet.workspace.path}") String workspacePath,
            @Value("${synapsenet.python.interpreter}") String pythonInterpreter
    ) {
        this.workingDirectory = Path.of(workspacePath);
        this.pythonInterpreter = pythonInterpreter;

        log.info("[PythonExecutor] Workspace: {}", workingDirectory);
        log.info("[PythonExecutor] Python: {}", pythonInterpreter);
    }

    private String getPythonExecutable() {
        if (pythonInterpreter != null && !pythonInterpreter.isBlank()) {
            return pythonInterpreter;
        }
        return "python3";
    }

    public PythonExecutionResult runPyCompile(String filePath) {
        List<String> command = new ArrayList<>();
        command.add(getPythonExecutable());
        command.add("-m");
        command.add("py_compile");
        command.add(filePath);
        return executeCommand(command, DEFAULT_TIMEOUT_SECONDS);
    }

    public PythonExecutionResult runPytest(String target) {

        List<String> command = new ArrayList<>();
        command.add(getPythonExecutable());
        command.add("-m");
        command.add("pytest");
        command.add(target);
        command.add("-v");
        command.add("--tb=long");
        command.add("--disable-warnings");
        command.add("--maxfail=1");

        return executeCommand(command, PYTEST_TIMEOUT_SECONDS);
    }

    public PythonExecutionResult runFile(String filePath) {

        List<String> command = new ArrayList<>();
        command.add(getPythonExecutable());
        command.add(filePath);

        return executeCommand(command, DEFAULT_TIMEOUT_SECONDS);
    }

    public PythonExecutionResult runScript(String script) {
        log.info("[PythonExecutor] Running Python script ({} chars)", script.length());

        try {
            Path tempScript = Files.createTempFile("synapsenet_", ".py");
            Files.writeString(tempScript, script);

            try {
                return runFile(tempScript.toString());
            } finally {
                Files.deleteIfExists(tempScript);
            }

        } catch (IOException e) {
            log.error("[PythonExecutor] Failed to create temp script: {}", e.getMessage());
            return PythonExecutionResult.error(
                "Failed to create temporary script file: " + e.getMessage()
            );
        }
    }

    private PythonExecutionResult executeCommand(List<String> command, int timeoutSeconds) {

        long startTime = System.currentTimeMillis();
        log.info("[PythonExecutor] Executing: {}", String.join(" ", command));

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDirectory.toFile());
            
            // ✅ CRITICAL FIX: Merge stderr into stdout
            // 
            // WHY: Pytest interleaves output to stdout and stderr chronologically.
            // When we keep streams separate and read them in parallel threads, then
            // concatenate (stdout + stderr), we DESTROY the chronological order.
            // 
            // This breaks regex patterns in PytestOutputAnalyzer that expect:
            //   "ERROR: not found: /path/to/file.py"
            //   "ModuleNotFoundError: No module named 'hypothesis'"
            //   "  File \"/path/to/pytest.py\", line 5, in <module>"
            // 
            // To appear in the correct order. With separate streams, the File "..."
            // line might appear BEFORE the ERROR line, breaking pattern matching.
            // 
            // SOLUTION: redirectErrorStream(true) merges stderr into stdout at the
            // OS level, preserving pytest's actual output order.
            builder.redirectErrorStream(true);

            Process process = builder.start();

            StringBuilder output = new StringBuilder();

            // Read merged stream (stdout + stderr in chronological order)
            Thread outThread = new Thread(() -> {
                try (BufferedReader reader =
                             new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception e) {
                    log.warn("[PythonExecutor] Error reading output: {}", e.getMessage());
                }
            });

            outThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.warn("[PythonExecutor] Process timed out after {} seconds", timeoutSeconds);
                return new PythonExecutionResult(
                        -1,
                        output.toString(),
                        "TIMEOUT after " + timeoutSeconds + " seconds",
                        System.currentTimeMillis() - startTime
                );
            }

            outThread.join(1000);

            String mergedOutput = output.toString();
            int exitCode = process.exitValue();

            log.info("[PythonExecutor] Exit code: {}, Output length: {} chars", 
                    exitCode, mergedOutput.length());

            // ✅ Return merged output as both stdout and stderr
            // Analyzer receives chronologically correct output
            return new PythonExecutionResult(
                    exitCode,
                    mergedOutput,  // stdout
                    mergedOutput,  // stderr (same - they're merged)
                    System.currentTimeMillis() - startTime
            );

        } catch (Exception e) {
            log.error("[PythonExecutor] Execution failed: {}", e.getMessage());
            return new PythonExecutionResult(
                    -2,
                    "",
                    "Python execution failed: " + e.getMessage(),
                    System.currentTimeMillis() - startTime
            );
        }
    }
}