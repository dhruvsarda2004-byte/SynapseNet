package com.synapsenet.orchestrator.dto;

public class CIRResult {

    private final String task;
    private final int iterations;
    private final double gamma;
    private final String decision;
    private final String finalAnswer;

    public CIRResult(
            String task,
            int iterations,
            double gamma,
            String decision,
            String finalAnswer
    ) {
        this.task = task;
        this.iterations = iterations;
        this.gamma = gamma;
        this.decision = decision;
        this.finalAnswer = finalAnswer;
    }

    public String getTask() {
        return task;
    }

    public int getIterations() {
        return iterations;
    }

    public double getGamma() {
        return gamma;
    }

    public String getDecision() {
        return decision;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }
}
