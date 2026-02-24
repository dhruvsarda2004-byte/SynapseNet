package com.synapsenet.core.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synapsenet.core.agent.ExecutorAgent;
import com.synapsenet.core.filesystem.FileSystemManager;
import com.synapsenet.core.filesystem.FileSystemManager.SearchResult;
import com.synapsenet.core.state.SharedState;

import java.util.List;

/**
 * RealToolExecutor - HARDENED Production-grade tool execution
 * * Features:
 * - Defensive JSON validation on all tool arguments
 * - Never returns null ToolResult
 * - Clear error messages with exitCode=1 on failures
 * - Integrates with FileSystemManager and PythonExecutor
 */
@Component
@Profile("ollama")
public class RealToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(RealToolExecutor.class);

    private final FileSystemManager fileSystem;
    private final PythonExecutor pythonExecutor;
    private final ObjectMapper jsonMapper;

    public RealToolExecutor(FileSystemManager fileSystem, 
                            PythonExecutor pythonExecutor) {
        this.fileSystem = fileSystem;
        this.pythonExecutor = pythonExecutor;
        this.jsonMapper = new ObjectMapper();
    }

    @Override
    public ToolResult execute(ExecutorAgent.ToolCall call) {
        return execute(call, null);
    }
    
    @Override
    public ToolResult execute(ExecutorAgent.ToolCall call, SharedState sharedState) {
        String tool = call.getTool();
        
        log.info("[ToolExecutor] Executing tool: {}", tool);

        try {
            ToolResult result = switch (tool) {
                case "read_file" -> executeReadFile(call.getArgs(), sharedState);
                case "read_lines" -> executeReadLines(call.getArgs());
                case "write_file" -> executeWriteFile(call.getArgs());
                case "replace_in_file" -> executeReplaceInFile(call.getArgs());
                case "grep" -> executeGrep(call.getArgs());
                case "list_files" -> executeListFiles(call.getArgs(), sharedState);
                case "file_tree" -> executeFileTree(call.getArgs(), sharedState);
                case "run_tests" -> executeRunTests(call.getArgs());
                case "run_python" -> executeRunPython(call.getArgs());
                default -> new ToolResult(
                	    tool,
                	    1,
                	    "",
                	    "Unknown tool: " + tool,
                	    null,
                	    null
                	);
            };

            
            // ✅ DEFENSIVE: Ensure result is never null
            if (result == null) {
                log.error("[ToolExecutor] Tool '{}' returned null result", tool);
                return new ToolResult(tool, 1, "", "Tool returned null result", null, null);

            }
            
            return result;
            
        } catch (Exception e) {
            log.error("[ToolExecutor] Tool execution failed: {}", e.getMessage(), e);
            return new ToolResult(
            	    tool,
            	    1,
            	    "",
            	    "Tool execution error: " + e.getMessage(),
            	    null,
            	    null
            	);

        }
    }
        

    /* ============================================================
       ✅ NEW: GENERIC ARG VALIDATION UTILITY
       ============================================================ */

    /**
     * Safely extract required text field from JSON.
     * Throws IllegalArgumentException if field is missing or null.
     */
    private String requireText(JsonNode json, String field, String toolName) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return node.asText();
    }

    /**
     * Safely extract optional text field with default value.
     */
    private String optionalText(JsonNode json, String field, String defaultValue) {
        if (!json.has(field)) {
            return defaultValue;
        }
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        return node.asText();
    }

    /**
     * Safely extract optional integer field with default value.
     */
    private int optionalInt(JsonNode json, String field, int defaultValue) {
        if (!json.has(field)) {
            return defaultValue;
        }
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        return node.asInt();
    }

    /* ============================================================
       TOOL IMPLEMENTATIONS - ALL HARDENED
       ============================================================ */

    /**
     * Read entire file
     * Args: {"path": "src/main.py"}
     */
    private ToolResult executeReadFile(String args, SharedState sharedState) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            
            // ✅ HARDENED: Defensive field extraction
            String path = requireText(json, "path", "read_file");
            
            String content = fileSystem.readFile(path);
            
            // Cache file content if SharedState is available
            if (sharedState != null) {
                sharedState.cacheFileRead(path, content);
            }
            
            return new ToolResult("read_file", 0, content, "", path, null);

            
        } catch (IllegalArgumentException e) {
            // ✅ CLEAN ERROR: Missing required field
            return new ToolResult(
                "read_file",
                1,
                "",
                e.getMessage(),
                null,
                null
            );
        } catch (FileSystemManager.FileSystemException e) {
            // ✅ CLEAN ERROR: File not found or read error
            return new ToolResult(
                "read_file",
                1,
                "",
                "Failed to read file: " + e.getMessage(),
                null,
                null
            );
        } catch (Exception e) {
            return new ToolResult(
                "read_file",
                1,
                "",
                "JSON parsing error: " + e.getMessage(),
                null,
                null
            );
        }

    }

    /**
     * Read specific line range
     * Args: {"path": "src/main.py", "start": 10, "end": 20}
     */
    private ToolResult executeReadLines(String args) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            
            // ✅ HARDENED: Defensive field extraction
            String path = requireText(json, "path", "read_lines");
            int start = optionalInt(json, "start", 1);
            int end = optionalInt(json, "end", -1);
            
            List<String> lines = fileSystem.readLines(path, start, end);
            String content = String.join("\n", lines);
            
            return new ToolResult(
            	    "read_lines",
            	    0,
            	    content,
            	    "",
            	    path,
            	    null
            	);

            	} catch (IllegalArgumentException e) {
            	    return new ToolResult(
            	        "read_lines",
            	        1,
            	        "",
            	        e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (FileSystemManager.FileSystemException e) {
            	    return new ToolResult(
            	        "read_lines",
            	        1,
            	        "",
            	        "Failed to read lines: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (Exception e) {
            	    return new ToolResult(
            	        "read_lines",
            	        1,
            	        "",
            	        "JSON parsing error: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	}

    }

    /**
     * Write content to file
     * Args: {"path": "src/main.py", "content": "def foo():\n    pass"}
     */
    private ToolResult executeWriteFile(String args) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            
            // ✅ HARDENED: Defensive field extraction
            String path = requireText(json, "path", "write_file");
            String content = requireText(json, "content", "write_file");
            
            fileSystem.writeFile(path, content);
            
            return new ToolResult(
            	    "write_file",
            	    0,
            	    "File written successfully",
            	    "",
            	    path,
            	    null
            	);

            	} catch (IllegalArgumentException e) {
            	    return new ToolResult(
            	        "write_file",
            	        1,
            	        "",
            	        e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (FileSystemManager.FileSystemException e) {
            	    return new ToolResult(
            	        "write_file",
            	        1,
            	        "",
            	        "Failed to write file: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (Exception e) {
            	    return new ToolResult(
            	        "write_file",
            	        1,
            	        "",
            	        "JSON parsing error: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	}

    }

    /**
     * Replace a multi-line block in file with ROBUST whitespace handling
     */
    private ToolResult executeReplaceInFile(String args) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            
            // ✅ HARDENED: Defensive field extraction
            String path = requireText(json, "path", "replace_in_file");
            String searchBlock = requireText(json, "search_block", "replace_in_file");
            String replaceBlock = requireText(json, "replace_block", "replace_in_file");

            log.info("[ToolExecutor] replace_in_file: searching in {}", path);
            log.debug("[ToolExecutor] Search block:\n{}", searchBlock);

            String originalContent = fileSystem.readFile(path);

            // Try exact match first
            int firstIndex = originalContent.indexOf(searchBlock);
            
            if (firstIndex != -1) {
                // Exact match found - check for duplicates
                int secondIndex = originalContent.indexOf(searchBlock, firstIndex + 1);
                if (secondIndex != -1) {
                	return new ToolResult(
                		    "replace_in_file",
                		    1,
                		    "",
                		    "Search block found multiple times - be more specific",
                		    path,
                		    null
                		);
                		}

                		// Perform replacement
                		String updatedContent = originalContent.replace(searchBlock, replaceBlock);
                		fileSystem.writeFile(path, updatedContent);

                		log.info("[ToolExecutor] Successfully replaced block in {} (exact match)", path);
                		return new ToolResult(
                		    "replace_in_file",
                		    0,
                		    "Block replaced successfully",
                		    "",
                		    path,
                		    null
                		);
            }

            // Exact match failed - try normalized matching
            log.info("[ToolExecutor] Exact match failed, trying normalized matching...");
            
            String normalizedSearch = normalizeWhitespace(searchBlock);
            String normalizedContent = normalizeWhitespace(originalContent);
            
            int normalizedIndex = normalizedContent.indexOf(normalizedSearch);
            
            if (normalizedIndex != -1) {
                String[] lines = originalContent.split("\n");
                String[] searchLines = searchBlock.split("\n");
                
                // Try to find a fuzzy match
                for (int i = 0; i <= lines.length - searchLines.length; i++) {
                    StringBuilder candidateBlock = new StringBuilder();
                    for (int j = 0; j < searchLines.length; j++) {
                        if (i + j < lines.length) {
                            candidateBlock.append(lines[i + j]);
                            if (j < searchLines.length - 1) {
                                candidateBlock.append("\n");
                            }
                        }
                    }
                    
                    String candidate = candidateBlock.toString();
                    
                    if (normalizeWhitespace(candidate).equals(normalizedSearch)) {
                        String updatedContent = originalContent.replace(candidate, replaceBlock);
                        fileSystem.writeFile(path, updatedContent);

                        log.info("[ToolExecutor] Successfully replaced block in {} (fuzzy match)", path);
                        return new ToolResult(
                            "replace_in_file",
                            0,
                            "Block replaced successfully (whitespace normalized)",
                            "",
                            path,
                            null
                        );
                    }

                }
            }

            // Still no match - provide helpful error with context
            log.warn("[ToolExecutor] Could not find search block in {}", path);
            
            String fileSnippet = getRelevantSnippet(originalContent, searchBlock);
            
            String errorMessage = String.format(
                "Search block not found in file.\n\n" +
                "You searched for:\n%s\n\n" +
                "Actual file contains:\n%s\n\n" +
                "Tip: Copy the EXACT text from the file, including whitespace.",
                truncate(searchBlock, 200),
                fileSnippet
            );
            
            return new ToolResult(
            	    "replace_in_file",
            	    1,
            	    "",
            	    errorMessage,
            	    path,
            	    null
            	);

            	} catch (IllegalArgumentException e) {
            	    return new ToolResult(
            	        "replace_in_file",
            	        1,
            	        "",
            	        e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (FileSystemManager.FileSystemException e) {
            	    return new ToolResult(
            	        "replace_in_file",
            	        1,
            	        "",
            	        "Failed to replace block: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (Exception e) {
            	    log.error("[ToolExecutor] Unexpected error in replace_in_file", e);
            	    return new ToolResult(
            	        "replace_in_file",
            	        1,
            	        "",
            	        "Failed to replace block: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	}

    }

    private String normalizeWhitespace(String text) {
        return text
            .replaceAll("\\r\\n", "\n")
            .replaceAll("\\r", "\n")
            .replaceAll("[ \\t]+", " ")
            .replaceAll(" *\n *", "\n")
            .trim();
    }

    private String getRelevantSnippet(String content, String searchBlock) {
        String[] searchLines = searchBlock.split("\n");
        String firstLine = "";
        for (String line : searchLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                firstLine = trimmed;
                break;
            }
        }
        
        if (firstLine.isEmpty()) {
            return content.substring(0, Math.min(300, content.length()));
        }
        
        String[] contentLines = content.split("\n");
        int foundAt = -1;
        for (int i = 0; i < contentLines.length; i++) {
            if (contentLines[i].trim().contains(firstLine) || 
                firstLine.contains(contentLines[i].trim())) {
                foundAt = i;
                break;
            }
        }
        
        if (foundAt == -1) {
            return content.substring(0, Math.min(300, content.length()));
        }
        
        int start = Math.max(0, foundAt - 2);
        int end = Math.min(contentLines.length, foundAt + 5);
        
        StringBuilder snippet = new StringBuilder();
        for (int i = start; i < end; i++) {
            snippet.append(contentLines[i]).append("\n");
        }
        
        return snippet.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
 
    /**
     * Search for pattern in files
     * Args: {"pattern": "def.*calculate", "path": "src/"}
     */
    private ToolResult executeGrep(String args) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            
            // ✅ HARDENED: Defensive field extraction
            String pattern = requireText(json, "pattern", "grep");
            String path = requireText(json, "path", "grep");
            
            List<SearchResult> results = fileSystem.grep(pattern, path);
            
            StringBuilder output = new StringBuilder();
            for (SearchResult result : results) {
                output.append(result.toString()).append("\n");
            }
            
            return new ToolResult(
            	    "grep",
            	    0,
            	    output.toString(),
            	    "",
            	    null,
            	    null
            	);

            	} catch (IllegalArgumentException e) {
            	    return new ToolResult(
            	        "grep",
            	        1,
            	        "",
            	        e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (FileSystemManager.FileSystemException e) {
            	    return new ToolResult(
            	        "grep",
            	        1,
            	        "",
            	        "Failed to search: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (Exception e) {
            	    return new ToolResult(
            	        "grep",
            	        1,
            	        "",
            	        "JSON parsing error: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	}

    }

    /**
     * List files in directory
     * Args: {"path": "src/"} or {} (defaults to ".")
     */
    private ToolResult executeListFiles(String args, SharedState state) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            String path = optionalText(json, "path", ".");

            List<String> files = fileSystem.listFiles(path);
            String output = String.join("\n", files);

            if (state != null) {
                state.setStructureDiscovered(true);
            }

            return new ToolResult(
            	    "list_files",
            	    0,
            	    output,
            	    "",
            	    null,
            	    null
            	);

            	} catch (FileSystemManager.FileSystemException e) {
            	    return new ToolResult(
            	        "list_files",
            	        1,
            	        "",
            	        "Failed to list files: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (Exception e) {
            	    return new ToolResult(
            	        "list_files",
            	        1,
            	        "",
            	        "JSON parsing error: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	}

    }

    /**
     * Show directory tree
     * Args: {"path": "src/", "depth": 3}
     */
    private ToolResult executeFileTree(String args, SharedState state) {
        try {
            JsonNode json = jsonMapper.readTree(args);

            String path = optionalText(json, "path", ".");
            int depth = optionalInt(json, "depth", 3);

            String tree = fileSystem.getFileTree(path, depth);

            if (state != null) {
                state.setStructureDiscovered(true);
            }

            return new ToolResult(
            	    "file_tree",
            	    0,
            	    tree,
            	    "",
            	    null,
            	    null
            	);

            	} catch (FileSystemManager.FileSystemException e) {
            	    return new ToolResult(
            	        "file_tree",
            	        1,
            	        "",
            	        "Failed to build tree: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (Exception e) {
            	    return new ToolResult(
            	        "file_tree",
            	        1,
            	        "",
            	        "JSON parsing error: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	}

    }

    /**
     * Run pytest tests
     * Args: {"test_file": "tests/test_main.py"} or {"directory": "tests/"}
     */
    private ToolResult executeRunTests(String args) {
        try {
            JsonNode json = jsonMapper.readTree(args);

            String target = ".";  // Force auto-discovery

            // Execute pytest
            PythonExecutionResult result = pythonExecutor.runPytest(target);

            // ✅ Create ToolResult
            ToolResult toolResult = new ToolResult(
            	    "run_tests",
            	    result.getExitCode(),
            	    result.getStdout(),
            	    result.getStderr(),
            	    null,
            	    target
            	);

            	log.info("[ToolExecutor] run_tests executed on target: {}, exitCode: {}",
            	         target, result.getExitCode());

            	return toolResult;

            	} catch (IllegalArgumentException e) {
            	    log.error("[ToolExecutor] Invalid args for run_tests: {}", e.getMessage());
            	    return new ToolResult(
            	        "run_tests",
            	        1,
            	        "",
            	        e.getMessage(),
            	        null,
            	        null
            	    );
            	} catch (Exception e) {
            	    log.error("[ToolExecutor] Failed to run tests: {}", e.getMessage(), e);
            	    return new ToolResult(
            	        "run_tests",
            	        1,
            	        "",
            	        "Failed to run tests: " + e.getMessage(),
            	        null,
            	        null
            	    );
            	}

    }

    /**
     * Run arbitrary Python script
     * Args: {"script": "print('hello')"} or {"file": "script.py"}
     */
    private ToolResult executeRunPython(String args) {
        try {
            JsonNode json = jsonMapper.readTree(args);
            
            PythonExecutionResult result;
            
            if (json.has("script")) {
                String script = requireText(json, "script", "run_python");
                result = pythonExecutor.runScript(script);
            } else if (json.has("file")) {
                String file = requireText(json, "file", "run_python");
                result = pythonExecutor.runFile(file);
            } else {
            	return new ToolResult(
            		    "run_python",
            		    1,
            		    "",
            		    "Must provide either 'script' or 'file' parameter",
            		    null,
            		    null
            		);
            		}

            		return new ToolResult(
            		    "run_python",
            		    result.getExitCode(),
            		    result.getStdout(),
            		    result.getStderr(),
            		    null,
            		    null
            		);

            		} catch (IllegalArgumentException e) {
            		    return new ToolResult(
            		        "run_python",
            		        1,
            		        "",
            		        e.getMessage(),
            		        null,
            		        null
            		    );
            		} catch (Exception e) {
            		    return new ToolResult(
            		        "run_python",
            		        1,
            		        "",
            		        "Failed to run Python: " + e.getMessage(),
            		        null,
            		        null
            		    );
            		}

    }
}