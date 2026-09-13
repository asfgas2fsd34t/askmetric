# AskMetric

简体中文 | [English](README_EN.md)

一个由 Java 业务服务和 Python Agent Runtime 组成的 chat-first 企业级 BI Agent 平台。

> 当前状态：T01 双向 Agent Run 与 T02 LangGraph Checkpoint 恢复技术探针已实现；业务持久化、Vue 工作区和后续分析能力按路线逐步交付。

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

## T02 Checkpoint 恢复探针

独立 Smoke Check 使用 PostgreSQL 保存 LangGraph Checkpoint。脚本先让一个 Python 容器在 `record_progress` 节点后退出，再启动新容器恢复同一个 Agent Run，并校验关联标识、事件序号以及已记录事件不会重复：

```bash
bash scripts/smoke-checkpoint.sh
docker compose -f infra/docker-compose.yml down -v
```

## T03 确定性 HTML 报告探针

Java 使用固定结构化分析、受控模板和内联 CSS 生成无需服务端即可打开的独立 HTML。命令会输出报告的 SHA-256：

```bash
mvn -q -f apps/server/pom.xml -DskipTests package
java -cp apps/server/target/classes dev.askmetric.server.report.ReportProbe target/report-probe.html
bash scripts/smoke-report.sh
```

## T04 Workspace 登录

Docker Compose 会启动 PostgreSQL、Keycloak、受保护的 Java API 和 Vue 工作区：

```bash
docker compose -f infra/docker-compose.yml up --build -d
bash scripts/smoke-workspace-identity.sh
```

打开 `http://localhost:5173`，使用本地演示身份 `alice` / `askmetric-demo` 登录。Vue 只能展示 Java 根据当前 Workspace Membership 确认的工作区；未认证 API 请求返回 `401`，不属于当前用户的 Workspace 选择返回 `403`。

Workspace、Workspace Membership、Workspace Policy 和 Conversation 由 PostgreSQL 持久化。Java 通过 MyBatis 在每条查询中绑定认证用户和经 Membership 验证的 Workspace，并按每个操作同时检查成员权限与策略：

```bash
bash scripts/smoke-workspace-isolation.sh
```

## T13 Demo Warehouse

独立 PostgreSQL Demo Warehouse 保存 `mrr-drop-v1` 固定合成数据集，覆盖 2025 年 1 月至 6 月的客户分层、套餐和订阅事件。已知植入事件会将 6 月 MRR 从 480000 美分降至 300000 美分；验证脚本会复现该结果，并确认重复重置得到完全一致的数据：

```bash
bash scripts/smoke-demo-warehouse.sh
```

需要单独恢复初始数据时运行：

```bash
bash scripts/reset-demo-warehouse.sh
```

## 首版范围

首版使用确定性的 B2B SaaS 合成数据，证明一条完整分析链路。它支持多轮对话、受治理的 SQL、带引用的 RAG、经确认的长期记忆、人工审批的语义目录变更、可重现的 HTML 报告、全链路 Trace 和公开评测。

首版明确不做通用助手、Dashboard 设计器、任意数据库自动理解、Multi-Agent 讨论框架或多数据库平台。
