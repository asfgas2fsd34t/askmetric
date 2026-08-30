# AskMetric

[简体中文](README.md) | English

Chat-first enterprise BI agent platform with Java business services and a Python Agent Runtime.

> Status: the T01 bidirectional Agent Run probe is implemented; persistence, the Vue workspace, and analysis capabilities will follow the roadmap.

AskMetric turns business questions into auditable analyses and self-contained HTML reports. It uses a Vue client, a Spring Boot modular monolith, and a Python LangGraph runtime while keeping data access, authorization, approvals, and side effects under Java governance.

## Project docs

- [Architecture](docs/architecture.md)
- [12-week roadmap](docs/roadmap.md)
- [Domain language](CONTEXT.md)
- [Architecture decisions](docs/adr/)
- [Pending issues](docs/pending-issues.md)
- [Agent conventions](AGENTS.md)

## Run T01 locally

Requires Docker Desktop, Java 21, and Python 3.12. Start RocketMQ, the Java Server, and the Python Agent:

```bash
docker compose -f infra/docker-compose.yml up --build -d
bash scripts/smoke-agent-run.sh
docker compose -f infra/docker-compose.yml down -v
```

The smoke check submits a message through the Conversation REST entry point, reads SSE, and verifies `accepted`, `progress`, and `completed` events. Contract tests can run independently:

```bash
mvn -f apps/server/pom.xml test
python -m pip install -r apps/agent/requirements.txt pytest
PYTHONPATH=apps/agent pytest -q apps/agent/tests
```

## Scope

The first release proves one complete B2B SaaS analysis workflow against deterministic synthetic data. It supports multi-turn conversations, governed SQL, cited RAG, confirmed memory, human-approved MCP actions, reproducible HTML reports, tracing, and public evaluations.

It deliberately does not attempt to be a general assistant, dashboard builder, automatic database-understanding system, multi-agent discussion framework, or multi-database platform.
