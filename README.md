# AskMetric

简体中文 | [English](README_EN.md)

一个由 Java 业务服务和 Python Agent Runtime 组成的 chat-first 企业级 BI Agent 平台。

> 当前状态：T01 双向 Agent Run 技术探针已实现；业务持久化、Vue 工作区和后续分析能力按路线逐步交付。

AskMetric 将业务问题转化为可审计分析和独立 HTML 报告。系统由 Vue 前端、Spring Boot 模块化单体和 Python LangGraph Runtime 组成，数据访问、权限、审批和副作用操作统一由 Java 治理。

## 项目文档

- [系统架构](docs/architecture.md)
- [12 周开发路线](docs/roadmap.md)
- [领域词汇](CONTEXT.md)
- [架构决策](docs/adr/)
- [待解决问题](docs/pending-issues.md)
- [Agent 协作约定](AGENTS.md)

## T01 本地运行

需要 Docker Desktop、Java 21 和 Python 3.12。启动 RocketMQ、Java Server 和 Python Agent：

```bash
docker compose -f infra/docker-compose.yml up --build -d
bash scripts/smoke-agent-run.sh
docker compose -f infra/docker-compose.yml down -v
```

Smoke Check 会从 Conversation REST 入口提交消息，读取 SSE，并校验 `accepted`、`progress` 和 `completed` 事件。契约测试可以分别运行：

```bash
mvn -f apps/server/pom.xml test
python -m pip install -r apps/agent/requirements.txt pytest
PYTHONPATH=apps/agent pytest -q apps/agent/tests
```

## T04 Workspace 登录

Docker Compose 会启动 Keycloak、受保护的 Java API 和 Vue 工作区：

```bash
docker compose -f infra/docker-compose.yml up --build -d
bash scripts/smoke-workspace-identity.sh
```

打开 `http://localhost:5173`，使用本地演示身份 `alice` / `askmetric-demo` 登录。Vue 只能展示 Java 根据当前 Workspace Membership 确认的工作区；未认证 API 请求返回 `401`，不属于当前用户的 Workspace 选择返回 `403`。

## 首版范围

首版使用确定性的 B2B SaaS 合成数据，证明一条完整分析链路。它支持多轮对话、受治理的 SQL、带引用的 RAG、经确认的长期记忆、人工审批的 MCP 操作、可重现的 HTML 报告、全链路 Trace 和公开评测。

首版明确不做通用助手、Dashboard 设计器、任意数据库自动理解、Multi-Agent 讨论框架或多数据库平台。
