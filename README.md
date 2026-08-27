# AskMetric

简体中文 | [English](README_EN.md)

一个由 Java 业务服务和 Python Agent Runtime 组成的 chat-first 企业级 BI Agent 平台。

> 当前状态：架构与领域设计已经完成，尚未开始编码实现。

AskMetric 将业务问题转化为可审计分析和独立 HTML 报告。系统由 Vue 前端、Spring Boot 模块化单体和 Python LangGraph Runtime 组成，数据访问、权限、审批和副作用操作统一由 Java 治理。

## 项目文档

- [系统架构](docs/architecture.md)
- [12 周开发路线](docs/roadmap.md)
- [领域词汇](CONTEXT.md)
- [架构决策](docs/adr/)
- [Agent 协作约定](AGENTS.md)

## 首版范围

首版使用确定性的 B2B SaaS 合成数据，证明一条完整分析链路。它支持多轮对话、受治理的 SQL、带引用的 RAG、经确认的长期记忆、人工审批的 MCP 操作、可重现的 HTML 报告、全链路 Trace 和公开评测。

首版明确不做通用助手、Dashboard 设计器、任意数据库自动理解、Multi-Agent 讨论框架或多数据库平台。
