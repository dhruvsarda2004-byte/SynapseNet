# SynapseNet 🧠

SynapseNet is a **multi-agent AI orchestration system built in Java** where independent agents collaborate to solve tasks through **planning, critique, and execution**.

Instead of relying on a single AI agent to do everything in one pass, SynapseNet enforces a structured workflow inspired by real-world engineering and decision-making processes.

---

## ❓ Problem Statement

Most AI systems today rely on a **single agent** to:
- Understand a task
- Plan a solution
- Execute it

This often leads to:
- Weak or incomplete planning
- No independent self-criticism
- Errors propagating directly into execution

SynapseNet explores whether **splitting intelligence into multiple specialized agents**
can improve robustness, clarity, and extensibility.

---

## 💡 Core Idea

Rather than one agent handling everything, SynapseNet separates responsibilities:

- **Planning** is handled by one agent
- **Evaluation** by another
- **Execution** by a third

Each agent operates independently with a clearly defined role.
This separation of concerns mirrors real-world workflows where plans are reviewed before being executed.

---

## 🏗️ Current Architecture (Day 1–12)

The system currently follows a deterministic, multi-agent pipeline:

User Task
↓
Planner Agent
↓
Critic Agent
↓
Executor Agent
↓
Final Output


### 🧩 Planner Agent
- Converts a raw user task into a structured plan
- Focuses only on task decomposition and high-level reasoning
- Does not perform execution

### 🔍 Critic Agent
- Reviews the planner’s output
- Identifies logical flaws, missing steps, or inefficiencies
- Acts as an independent verifier before execution

### ⚙️ Executor Agent
- Executes the finalized plan step-by-step
- Produces the final output
- Does not modify planning or evaluation logic

This explicit orchestration ensures that execution only happens after a plan has been independently reviewed.

---

## 🚀 Why Multi-Agent Instead of Single-Agent?

### Single-Agent Systems:
- Mix planning, evaluation, and execution in one step
- Rarely challenge their own assumptions
- Are harder to extend or audit

### SynapseNet’s Multi-Agent Approach:
- Enforces planning before execution
- Introduces independent critique
- Improves modularity and extensibility
- Makes reasoning steps explicit and debuggable

The goal is not just better outputs, but **better structure and accountability in decision-making**.

---

## ⚠️ Current Limitations

The current implementation intentionally keeps the system simple. Known limitations include:

- The Critic agent cannot yet force re-planning
- No Mediator or consensus mechanism exists
- No quantitative benchmarks comparing single-agent vs multi-agent outputs
- No frontend visualization of agent interactions

These limitations define the roadmap rather than weaknesses.

---

## 🛠️ Tech Stack

- Java
- Spring Boot
- Maven
- Git & GitHub

---

## 🗺️ Roadmap

Planned future improvements include:

- Introducing a **Mediator agent** for consensus and conflict resolution
- Enabling **critic-driven re-planning loops**
- Adding a frontend to visualize agent interactions
- Benchmarking results against single-agent approaches
- Exploring a research-paper publication focused on architecture and evaluation

---

## 📌 Project Philosophy

SynapseNet is built incrementally and ethically, with a clear commit history reflecting day-by-day progress.
The focus is on **architecture, clarity, and reasoning**, not prompt hacking or black-box behavior.

---

## 📂 Project Status

**Current Stage:** Core multi-agent architecture implemented (Day 1–12)  
**Next Stage:** Consensus, iteration, and evaluation

---
