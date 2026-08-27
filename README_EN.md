# AskMetric

[简体中文](README.md) | English

Chat-first enterprise BI agent platform with Java business services and a Python Agent Runtime.

> Status: architecture and domain design complete; implementation has not started.

AskMetric turns business questions into auditable analyses and self-contained HTML reports. It uses a Vue client, a Spring Boot modular monolith, and a Python LangGraph runtime while keeping data access, authorization, approvals, and side effects under Java governance.

## Project docs

- [Architecture](docs/architecture.md)
- [12-week roadmap](docs/roadmap.md)
- [Domain language](CONTEXT.md)
- [Architecture decisions](docs/adr/)
- [Agent conventions](AGENTS.md)

## Scope

The first release proves one complete B2B SaaS analysis workflow against deterministic synthetic data. It supports multi-turn conversations, governed SQL, cited RAG, confirmed memory, human-approved MCP actions, reproducible HTML reports, tracing, and public evaluations.

It deliberately does not attempt to be a general assistant, dashboard builder, automatic database-understanding system, multi-agent discussion framework, or multi-database platform.
