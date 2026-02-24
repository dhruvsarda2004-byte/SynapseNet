package com.synapsenet.core.state;

import com.synapsenet.core.critic.CriticFeedback;
import com.synapsenet.core.executor.ExecutionResult;
import com.synapsenet.core.executor.TestResults;
import com.synapsenet.core.planner.PlannerOutput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SharedState — mutable shared context threaded through the entire CIR loop.
 *
 * Owned exclusively by SimpleTaskOrchestrator. Passed by reference to agents
 * that need to read context (PlannerAgent, ExecutorAgent, MediatorAgent).
 * Only the Orchestrator and ExecutorAgent mutate state.
 *
 * BENCHMARK NOTE: toBenchmarkResult() has been removed. Benchmark logging is
 * handled inline in SimpleTaskOrchestrator.logBenchmark() with no external DTO.
 */
public class SharedState {

    private static final Logger log = LoggerFactory.getLogger(SharedState.class);

    public static final String SUBTYPE_NO_TESTS_FOUND = "NO_TESTS_FOUND";
    public static final String SUBTYPE_IMPORT_ERROR   = "IMPORT_ERROR";
    public static final String SUBTYPE_SYNTAX_ERROR   = "SYNTAX_ERROR";
    public static final String SUBTYPE_UNKNOWN        = "UNKNOWN";

    private final String goal;

    private PlannerOutput currentPlan;

    private int currentTaskIndex;
    private int attemptsOnCurrentTask;
    private int totalIterations;

    private final List<ExecutionResult> executionHistory;
    private final List<CriticFeedback>  critiqueHistory;

    private RepairPhase currentPhase;
    private TestResults lastTestResults;

    private final List<String> modifiedFiles;

    private String collectionFailureModule;

    private String collectionFailureSubtype = null;
    private String failingArtifact          = null;
    private String collectionFailureReason  = null;

    // Line number of the failure within the artifact file.
    // -1 means unknown (fallback to first N lines).
    private int failingArtifactLine = -1;

    // Tool error tracking for Mediator escalation ladder
    private String lastToolError        = null;
    private int    consecutiveToolErrors = 0;

    // Root cause analysis from REPAIR_ANALYZE phase
    private RootCauseAnalysis lastRootCauseAnalysis = null;

    // Repair history (capped)
    private static final int MAX_REPAIR_HISTORY_SIZE = 5;
    private final List<RepairAttempt> repairHistory   = new ArrayList<>();

    private final Map<String, String> recentFileReads = new HashMap<>();
    private static final int MAX_FILE_CONTENT_LINES = 500;

    private boolean structureDiscovered = false;

    private int replanCount   = 0;
    private int toolCallCount = 0;

    // ✅ FIX 2: Failure observation tracking (Success Invariant)
    // Track whether a failure was actually observed before attempting repair
    // Prevents false SUCCESS when tests happen to pass without our patch
    private boolean failureObserved = false;

    // =========================================================================
    // Constructor
    // =========================================================================

    public SharedState(String goal) {
        this.goal                  = goal;
        this.currentTaskIndex      = 0;
        this.attemptsOnCurrentTask = 0;
        this.totalIterations       = 0;
        this.executionHistory      = new ArrayList<>();
        this.critiqueHistory       = new ArrayList<>();
        this.currentPhase          = RepairPhase.REPRODUCE;
        this.lastTestResults       = null;
        this.modifiedFiles         = new ArrayList<>();
        this.collectionFailureModule = null;
    }

    // =========================================================================
    // Phase
    // =========================================================================

    public RepairPhase getCurrentPhase() { return currentPhase; }

    public void setCurrentPhase(RepairPhase phase) {
        if (this.currentPhase != phase) {
            log.info("[State] Phase transition: {} → {}", this.currentPhase, phase);
            this.currentPhase = phase;
        }
    }

    // =========================================================================
    // Test results
    // =========================================================================

    public void setLastTestResults(TestResults results) {
        this.lastTestResults = results;

        if (results != null && results.allPassed()) {
            if (collectionFailureModule != null) {
                log.info("[State] Tests passed — clearing collection failure module");
                this.collectionFailureModule = null;
            }
            if (collectionFailureSubtype != null || failingArtifact != null) {
                log.info("[State] Tests passed — clearing collection failure metadata (subtype={}, artifact={})",
                        collectionFailureSubtype, failingArtifact);
                this.collectionFailureSubtype = null;
                this.failingArtifact          = null;
                this.collectionFailureReason  = null;
                this.failingArtifactLine      = -1;
            }
        }
    }

    public TestResults getLastTestResults() { return lastTestResults; }

    // =========================================================================
    // Modified files
    // =========================================================================

    public void addModifiedFile(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return;
        String normalized = normalizePath(filePath);
        if (!modifiedFiles.contains(normalized)) {
            modifiedFiles.add(normalized);
            log.info("[State] Tracked modified file: {}", normalized);
        }
    }

    public List<String> getModifiedFiles() { return new ArrayList<>(modifiedFiles); }

    public void clearModifiedFiles() {
        int count = modifiedFiles.size();
        modifiedFiles.clear();
        if (count > 0) log.info("[State] Cleared {} modified file(s)", count);
    }

    // =========================================================================
    // Iteration tracking
    // =========================================================================

    public void incrementTotalIterations()  { totalIterations++; }
    public void incrementTaskAttempts()     { attemptsOnCurrentTask++; }
    public void resetTaskAttempts()         { attemptsOnCurrentTask = 0; }

    public void advanceToNextTask() {
        currentTaskIndex++;
        attemptsOnCurrentTask = 0;
    }

    public void updatePlan(PlannerOutput newPlan) {
        this.currentPlan           = newPlan;
        this.currentTaskIndex      = 0;
        this.attemptsOnCurrentTask = 0;
    }

    public String getCurrentTask() {
        if (currentPlan == null
                || currentPlan.getInvestigationSteps() == null
                || currentPlan.getInvestigationSteps().isEmpty()) {
            return null;
        }
        if (currentTaskIndex >= currentPlan.getInvestigationSteps().size()) {
            return null;
        }
        return currentPlan.getInvestigationSteps().get(currentTaskIndex);
    }

    public boolean isOnLastTask() {
        if (currentPlan == null
                || currentPlan.getInvestigationSteps() == null
                || currentPlan.getInvestigationSteps().isEmpty()) {
            return false;
        }
        return currentTaskIndex == currentPlan.getInvestigationSteps().size() - 1;
    }

    public boolean isPlanExhausted() {
        if (currentPlan == null
                || currentPlan.getInvestigationSteps() == null
                || currentPlan.getInvestigationSteps().isEmpty()) {
            return true;
        }
        return currentTaskIndex >= currentPlan.getInvestigationSteps().size();
    }

    // =========================================================================
    // History recording
    // =========================================================================

    public void recordExecution(ExecutionResult result) {
        executionHistory.add(result);
        if (result.getModifiedFiles() != null) {
            for (String file : result.getModifiedFiles()) addModifiedFile(file);
        }
    }

    public void recordCritique(CriticFeedback feedback) { critiqueHistory.add(feedback); }

    public List<ExecutionResult> getRecentExecutions(int n) {
        int size  = executionHistory.size();
        int start = Math.max(0, size - n);
        return new ArrayList<>(executionHistory.subList(start, size));
    }

    public List<CriticFeedback> getCritiqueHistory() { return new ArrayList<>(critiqueHistory); }

    // =========================================================================
    // Accessors
    // =========================================================================

    public String  getGoal()                    { return goal; }
    public int     getCurrentTaskIndex()        { return currentTaskIndex; }
    public int     getAttemptsOnCurrentTask()   { return attemptsOnCurrentTask; }
    public int     getTotalIterations()         { return totalIterations; }
    public boolean isStructureDiscovered()      { return structureDiscovered; }

    public void setStructureDiscovered(boolean discovered) {
        this.structureDiscovered = discovered;
        if (discovered) log.info("[State] Project structure marked as DISCOVERED");
    }

    // =========================================================================
    // Collection failure metadata
    // =========================================================================

    public String getCollectionFailureModule() { return collectionFailureModule; }

    public void setCollectionFailureModule(String module) {
        this.collectionFailureModule = normalizePath(module);
        if (this.collectionFailureModule != null)
            log.info("[State] Collection failure module: {}", this.collectionFailureModule);
    }

    public String getCollectionFailureSubtype() { return collectionFailureSubtype; }

    public void setCollectionFailureSubtype(String subtype) {
        this.collectionFailureSubtype = subtype;
        if (subtype != null) log.info("[State] Collection failure subtype: {}", subtype);
    }

    public String getFailingArtifact() { return failingArtifact; }

    public void setFailingArtifact(String artifact) {
        if (artifact == null || artifact.equals(".") || artifact.equals("..")) {
            this.failingArtifact = null;
            return;
        }
        this.failingArtifact = normalizePath(artifact);
        if (this.failingArtifact != null)
            log.info("[State] Failing artifact: {}", this.failingArtifact);
    }

    public int getFailingArtifactLine() { return failingArtifactLine; }

    public void setFailingArtifactLine(int line) {
        this.failingArtifactLine = line;
        if (line > 0) log.info("[State] Failing artifact line: {}", line);
    }

    public String getCollectionFailureReason() { return collectionFailureReason; }

    public void setCollectionFailureReason(String reason) {
        this.collectionFailureReason = reason;
        if (reason != null) log.info("[State] Collection failure reason: {}", reason);
    }

    public boolean hasCollectionFailureArtifact() { return failingArtifact != null; }

    // =========================================================================
    // Tool error tracking
    // =========================================================================

    public String getLastToolError()           { return lastToolError; }
    public int    getConsecutiveToolErrors()   { return consecutiveToolErrors; }

    public void setLastToolError(String error) {
        this.lastToolError = error;
        if (error != null) {
            String preview = error.length() > 80 ? error.substring(0, 80) + "..." : error;
            log.warn("[State] Tool error: {}", preview);
        }
    }

    public void incrementConsecutiveToolErrors() {
        this.consecutiveToolErrors++;
        log.warn("[State] Consecutive tool errors: {}", consecutiveToolErrors);
    }

    public void clearToolErrorState() {
        if (lastToolError != null || consecutiveToolErrors > 0)
            log.info("[State] Clearing tool error state ({} consecutive)", consecutiveToolErrors);
        this.lastToolError        = null;
        this.consecutiveToolErrors = 0;
    }

    // =========================================================================
    // Root cause analysis
    // =========================================================================

    public void setRootCauseAnalysis(RootCauseAnalysis analysis) {
        this.lastRootCauseAnalysis = analysis;
        if (analysis != null && analysis.isValid())
            log.info("[State] Root cause analysis stored: {}", analysis);
    }

    public void clearRootCauseAnalysis() {
        if (lastRootCauseAnalysis != null)
            log.info("[State] Clearing root cause analysis for fresh REPAIR_ANALYZE");
        this.lastRootCauseAnalysis = null;
    }

    public RootCauseAnalysis getLastRootCauseAnalysis() { return lastRootCauseAnalysis; }

    public boolean hasValidRootCauseAnalysis() {
        return lastRootCauseAnalysis != null && lastRootCauseAnalysis.isValid();
    }

    // =========================================================================
    // Repair history
    // =========================================================================

    public void addRepairAttempt(RepairAttempt attempt) {
        if (repairHistory.size() >= MAX_REPAIR_HISTORY_SIZE) repairHistory.remove(0);
        repairHistory.add(attempt);
        log.info("[State] RepairAttempt #{} recorded (outcome={}, history={})",
                attempt.getAttemptNumber(), attempt.getOutcome(), repairHistory.size());
    }

    public List<RepairAttempt> getRepairHistory() {
        return Collections.unmodifiableList(repairHistory);
    }

    // =========================================================================
    // Counters (for inline benchmark logging — no DTO required)
    // =========================================================================

    public void incrementReplanCount()   { replanCount++;   log.info("[State] REPLAN count: {}", replanCount); }
    public void incrementToolCallCount() { toolCallCount++; }
    public int  getReplanCount()         { return replanCount; }
    public int  getToolCallCount()       { return toolCallCount; }

    // ✅ FIX 2: Failure observation tracking
    public void markFailureObserved() {
        this.failureObserved = true;
        log.info("[State] Failure observed — repair will be required");
    }
    public boolean wasFailureObserved() {
        return failureObserved;
    }

    // =========================================================================
    // File cache
    // =========================================================================

    public boolean hasReadFile(String filePath) {
        return recentFileReads.containsKey(normalizePath(filePath));
    }

    public void cacheFileRead(String path, String content) {
        if (path == null || content == null) return;
        String normalizedPath = normalizePath(path);
        int    originalLines  = content.split("\n").length;
        String truncated      = truncateContent(content, MAX_FILE_CONTENT_LINES);
        int    cachedLines    = truncated.split("\n").length;
        recentFileReads.put(normalizedPath, truncated);
        if (originalLines != cachedLines) {
            log.info("[State] Cached file: {} ({} lines, truncated from {})",
                    normalizedPath, cachedLines, originalLines);
        } else {
            log.info("[State] Cached file: {} ({} lines)", normalizedPath, cachedLines);
        }
    }

    public Map<String, String> getRecentFileReads() {
        return Collections.unmodifiableMap(recentFileReads);
    }

    public void clearFileCache() {
        int size = recentFileReads.size();
        recentFileReads.clear();
        this.structureDiscovered     = false;
        this.collectionFailureModule = null;
        this.collectionFailureSubtype = null;
        this.failingArtifact          = null;
        this.collectionFailureReason  = null;
        this.failingArtifactLine      = -1;
        if (size > 0) log.info("[State] Cleared {} cached file(s) and reset discovery", size);
    }

    public boolean hasFileContext() { return !recentFileReads.isEmpty(); }

    /**
     * Soft reset — clears patch/tool state but preserves structural knowledge.
     *
     * PRESERVED intentionally:
     *   recentFileReads      — structural knowledge planner needs
     *   structureDiscovered  — prevents redundant rediscovery
     *   lastTestResults      — failure context for revisePlan()
     *   failingArtifact      — revisePlan() needs grounded paths
     *   failingArtifactLine  — preserved alongside artifact
     *   collectionFailureReason — used for grounded prompts
     *   lastRootCauseAnalysis — preserved across REPLAN for prompt injection
     */
    public void softReset() {
        this.collectionFailureModule  = null;
        this.collectionFailureSubtype = null;
        this.lastToolError            = null;
        this.consecutiveToolErrors    = 0;
        log.info("[State] Soft reset: collection metadata cleared. " +
                 "File cache preserved ({} files). Artifact preserved: {}. Modified: {} (clear separately)",
                 recentFileReads.size(), failingArtifact, modifiedFiles.size());
    }

    // =========================================================================
    // Path utilities
    // =========================================================================

    public String normalizePath(String path) {
        if (path == null) return null;
        String normalized = path.startsWith("./") ? path.substring(2) : path;
        normalized = normalized.replaceAll("/$",  "");
        normalized = normalized.replaceAll("/+",  "/");
        return normalized;
    }

    /**
     * Truncate file content to maxLines, keeping 80% from the start and 20% from the end.
     *
     * The truncation marker uses a distinctive prefix ("# <<<") that is:
     *   - Valid Python syntax (a comment) — won't cause parse errors if injected into prompt
     *   - Unlikely to appear in real source code
     *   - Consistently formatted so normalizeForSearch in RootCauseAnalysis strips it
     *
     * WHY this matters: the marker is stored in the file cache and passed to
     * RootCauseAnalysis.searchBlockExistsInContent(). If the marker were freeform text
     * it could partially match a search block spanning the truncation boundary,
     * producing a false "not found" rejection of a valid analysis.
     */
    private String truncateContent(String content, int maxLines) {
        String[] lines = content.split("\n");
        if (lines.length <= maxLines) return content;

        int keepStart = (int) (maxLines * 0.8);
        int keepEnd   = maxLines - keepStart;

        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < keepStart; i++) truncated.append(lines[i]).append("\n");
        truncated.append("# <<< TRUNCATED: ")
                 .append(lines.length - maxLines)
                 .append(" lines omitted >>>\n");
        for (int i = lines.length - keepEnd; i < lines.length; i++) truncated.append(lines[i]).append("\n");
        return truncated.toString();
    }
}