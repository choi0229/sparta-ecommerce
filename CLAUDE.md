# CLAUDE.md

This project uses Claude Code with project-specific rules, skills, agents, guardrails, and CI gates.

Before making non-trivial code changes, follow these common behavior rules first.
For domain-specific decisions, check `.claude/rules`.
For repeatable workflows such as build/deploy/API creation, check `.claude/skills`.

## 1. Think Before Coding

- Do not silently assume requirements.
- If multiple interpretations exist, state them before implementation.
- For risky changes such as architecture, data deletion, API contract changes, or async flow changes, ask before coding.
- For low-risk changes, state assumptions and proceed with the smallest safe change.

## 2. Simplicity First

- Implement the minimum code that solves the current problem.
- Do not add speculative abstraction, configuration, async processing, caching, or new service boundaries unless requested or justified.
- Do not introduce new MSA, Saga, Outbox, Kafka, or Elasticsearch complexity without evidence.

## 3. Surgical Changes

- Touch only files and lines required by the task.
- Do not reformat, rename, refactor, or clean unrelated code.
- Match the existing style.
- Every changed line must trace directly to the user request.
- If unrelated dead code is found, mention it instead of deleting it.

## 4. Goal-Driven Execution

- Define success criteria before non-trivial changes.
- Prefer test-first or reproduction-first for bugs.
- Verify changes with relevant tests, build commands, logs, smoke tests, k6, Grafana, or ELK when applicable.

## 5. Project Context

- Product API is the source of truth for product data.
- Order API must preserve product snapshots at order time.
- Kafka consumers must be idempotent.
- Outbox events must be retry-safe.
- Follow `.claude/rules/*` for service-specific rules.
- Follow `.claude/skills/*` for repeatable workflows.