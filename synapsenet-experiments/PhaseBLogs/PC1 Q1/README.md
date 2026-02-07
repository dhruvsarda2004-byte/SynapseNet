# PC1-Q1 — CRISPR-Cas9 Molecular Explanation

## Task
Explain how CRISPR-Cas9 works at a molecular level in a single line.

## Model
- gemini-flash-latest

## Pipelines Evaluated
- P1: Planner Only
- P2: Planner + Executor
- P3: Planner + Critic + Executor
- P4: Planner + Critic + Mediator + Executor
- P5: Full Iterative CIR

## Storage Policy
- `runs.jsonl` contains raw, immutable model outputs
- `evaluations.json` contains human scoring and notes
- No outputs are edited or reformatted

## Purpose
Evaluate how increasing agent depth affects correctness, clarity,
and self-correction under a constrained explanation task.
