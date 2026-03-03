package com.synapsenet.core.state;

import com.synapsenet.core.critic.CriticFeedback;
import com.synapsenet.core.executor.ExecutionResult;
import com.synapsenet.core.executor.TestResults;
import com.synapsenet.core.planner.PlannerOutput;

import com.synapsenet.core.util.PathNormalizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // [FIX] LinkedHashMap guarantees insertion-order iteration across all prompt-building
    // sites (buildRepairAnalyzePrompt, buildExecutionPrompt, buildCachedFileContent).
    // HashMap provided no ordering guarantee — nondeterministic file order in prompts
    // triggered the LLM "lost-in-the-middle" effect on multi-file repair cycles.
    private final Map<String, String> recentFileReads = new LinkedHashMap<>();

    // [FIX] Raised from 500 to 10000.
    // The 500-line cap truncated large files (e.g. 1222-line acceptance_test.py to 501 lines),
    // making it impossible for ExecutorAgent.extractFileWindow() to center on the real failure
    // line. The windowing layer already handles context budget — the cache must hold the full
    // file so windowing can slice any region of it.
    private static final int MAX_FILE_CONTENT_LINES = 10_000;

    private boolean structureDiscovered = false;

    // Stores the raw output of the file_tree or list_files tool captured during REPRODUCE.
    // Injected into the REPAIR_ANALYZE prompt so the LLM can see real workspace paths
    // instead of guessing — the primary fix for artifactPath hallucinations like '_src/tokenbucket.py'.
    // Cleared alongside structureDiscovered in clearFileContentCache() after workspace restore.
    private String workspaceFileTree = null;

    // [Fix 5] Authoritative workspace file index — populated from FileSystemManager.getAllFilePaths()
    // during executeListFiles / executeFileTree. Never cleared (persists across REPLAN cycles).
    // Used by setFailingArtifact() (Fix 6) to reject hallucinated paths.
    // PathNormalizer.normalize() applied at write time — all lookups are O(1).
    private final Set<String> workspaceFiles = new LinkedHashSet<>();

    private int replanCount   = 0;
    private int toolCallCount = 0;

    // Deterministic FSM lifecycle.
    // MUTATION: Only SimpleTaskOrchestrator may call lifecycle.transitionTo().
    // READ:     All agents may call getRepairState() or getLifecycle().
    private final RepairLifecycle lifecycle = new RepairLifecycle();

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

    /** Store the raw file_tree / list_files output for later injection into REPAIR_ANALYZE. */
    public void setWorkspaceFileTree(String fileTree) {
        this.workspaceFileTree = fileTree;
        if (fileTree != null) {
            log.info("[State] Workspace file tree cached ({} chars)", fileTree.length());
        }
    }

    /** Returns the cached workspace file tree, or null if not yet discovered. */
    public String getWorkspaceFileTree() {
        return workspaceFileTree;
    }

    // =========================================================================
    // Workspace file index  [Fix 5]
    // =========================================================================

    /**
     * Populate the authoritative workspace file index from deterministically-walked paths.
     *
     * Called by RealToolExecutor after list_files or file_tree succeeds, using
     * FileSystemManager.getAllFilePaths() — never by parsing ASCII tree output.
     *
     * All paths normalized via PathNormalizer before storage.
     * The set is never cleared (persists across REPLAN restore cycles) so that
     * setFailingArtifact() [Fix 6] can always validate against it.
     */
    public void setWorkspaceFiles(Collection<String> files) {
        workspaceFiles.clear();
        for (String f : files) {
            workspaceFiles.add(PathNormalizer.normalize(f));
        }
        log.info("[State] Workspace file index updated: {} files", workspaceFiles.size());
    }

    /**
     * Return true if the given path (after normalization) is in the authoritative
     * workspace file index. Returns true vacuously when the index is empty
     * (not yet populated — e.g. first iteration before any file_tree call).
     */
    public boolean isValidWorkspacePath(String path) {
        if (workspaceFiles.isEmpty()) return true; // vacuously valid — index not yet built
        return workspaceFiles.contains(PathNormalizer.normalize(path));
    }
    
    /**
     * Return the authoritative workspace file list as an ordered List.
     * Used by REPAIR_ANALYZE for index-based artifact selection.
     * Returns empty list if the index has not yet been populated.
     */
    public List<String> getWorkspaceFiles() {
        return new ArrayList<>(workspaceFiles);
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
        String normalized = normalizePath(artifact);

        // [Fix 6] Reject artifact paths not present in the authoritative workspace index.
        // Prevents hallucinated paths (e.g. "ratelimiter/token_bucket.py") from entering
        // state when the real file is "ratelimit/bucket.py".
        // Guard is vacuously skipped when workspaceFiles is empty (index not yet built).
        if (!workspaceFiles.isEmpty() && !isValidWorkspacePath(normalized)) {
            log.warn("[State] Rejecting failing artifact '{}' — not found in workspace index ({}  known files)",
                     normalized, workspaceFiles.size());
            this.failingArtifact = null;
            return;
        }

        this.failingArtifact = normalized;
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

    // =========================================================================
    // Deterministic FSM lifecycle
    // =========================================================================

    /**
     * Returns the RepairLifecycle FSM.
     *
     * WRITE: Only SimpleTaskOrchestrator should call lifecycle.transitionTo().
     * READ:  All callers should prefer getRepairState() for simple reads.
     */
    public RepairLifecycle getLifecycle() {
        return lifecycle;
    }

    /**
     * Read-only access to current RepairState.
     * Safe to call from Mediator, Executor, Planner, Critic.
     */
    public RepairState getRepairState() {
        return lifecycle.getCurrentState();
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

    /**
     * Clear only the in-memory file content cache and reset structure discovery.
     *
     * Use after workspace restore to evict stale cached content.
     * Does NOT touch failure metadata (failingArtifact, collectionFailureSubtype, etc.)
     * so the Planner can still read grounded paths after a REPLAN with modification.
     */
    public void clearFileContentCache() {
        int size = recentFileReads.size();
        recentFileReads.clear();
        this.structureDiscovered = false;
        this.workspaceFileTree   = null;
        if (size > 0) log.info("[State] Cleared {} cached file(s), reset structure discovery and file tree", size);
    }

    /**
     * Clear failure metadata: failingArtifact, collectionFailureSubtype,
     * collectionFailureReason, collectionFailureModule, failingArtifactLine.
     *
     * Use after workspace restore to prevent stale failure signals from carrying
     * into the next REPRODUCE cycle.
     * Does NOT touch the file content cache — call clearFileContentCache() separately.
     */
    public void clearFailureContext() {
        this.collectionFailureModule  = null;
        this.collectionFailureSubtype = null;
        this.failingArtifact          = null;
        this.collectionFailureReason  = null;
        this.failingArtifactLine      = -1;
        log.info("[State] Failure context cleared (artifact, subtype, reason, line reset)");
    }

    /**
     * @deprecated Use {@link #clearFileContentCache()} + {@link #clearFailureContext()} explicitly.
     *
     * Retained for backward compatibility. Equivalent to calling both methods in sequence.
     * Hidden side effects: also clears failingArtifact and structureDiscovered, which
     * violates softReset()'s documented contract when called immediately before it.
     * Prefer the two-method form so call sites are explicit about what they clear.
     */
    @Deprecated
    public void clearFileCache() {
        clearFileContentCache();
        clearFailureContext();
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

    /**
     * Delegates to {@link PathNormalizer#normalize(String)} — single source of truth.
     *
     * Retained as an instance method for call sites that already have a SharedState
     * reference, but all normalization logic lives in PathNormalizer.
     */
    public String normalizePath(String path) {
        return PathNormalizer.normalize(path);
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
     *
     * NOTE: MAX_FILE_CONTENT_LINES is set to 10,000 so truncation effectively never
     * fires for real-world Python files. ExecutorAgent.extractFileWindow() handles context
     * budget by injecting only CONTEXT_WINDOW_LINES around the failure site.
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