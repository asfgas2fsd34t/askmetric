# 待解决问题

本文档记录 AskMetric 当前技术探针中已经确认、但暂不影响 T01 验收的问题。每个问题都需要在进入生产持久化或并发压测前重新评估。

## Agent 事件链路

### AGENT-003 真实链路中 finding 事件未落地

- **现状**：T18 在 compose 全链路验证中，下钻运行的受治理查询、查询授权、Evidence Snapshot 落库和前端证据面板展示均正常，但运行停在 `PROGRESS`（阶段 retrieving），`agent.run.finding` 与终态事件始终未持久化；集成测试（在 service 边界注入事件）与 Python 单测（fake 网关）均通过。
- **问题**：疑似 Python 发出的 finding 事件被 Java 侧拒绝后进入 RocketMQ 无限重投，或 Python 在查询成功后的推导/发布环节挂起；尚未定位到具体环节。
- **为什么暂缓**：不影响 T18 已验证的查询治理与证据链路；下钻结论在真实链路的最后一步缺失，确定性测试覆盖了等价路径。
- **建议方案**：给 `LiveRocketMqGateway.consumeEvent` 的拒绝路径加结构化日志；在 compose 环境重放一次下钻并对比 Python producer 与 Java consumer 日志；必要时为 finding 事件补一条端到端 smoke。
- **验收标准**：compose 全链路下钻运行能到达终态，快照包含 verified 发现；重投不再无限循环。

## 并发与 SSE

### CONC-002 SSE 注册与补发共享同步锁

- **现状**：`AgentRunSseHub.registerAndReplay` 和 `publishPersisted` 都是 `synchronized`。注册 SSE、查询并补发数据库事件期间，其他运行的事件推送会等待。
- **问题**：连接重连或历史事件较多时，重放过程会占用全局锁；网络发送发生在锁内，慢客户端可能阻塞所有 Agent Run 的事件追加。
- **为什么暂缓**：注册后重放的原子性可以避免事件丢失或顺序错乱，T01 事件量小且只用于技术探针。
- **建议方案**：改为按 `runId` 隔离订阅状态；使用数据库游标或事件序号进行重放，配合提交后的异步广播。必要时为每个运行建立单线程事件队列，避免在锁内执行网络 I/O。
- **验收标准**：重连时不丢事件、不重复处理已确认序号；新事件不会排在历史事件之前；一个运行的重放或慢连接不会阻塞其他运行。

## 处理顺序

1. 为 `runId` 并发追加、RocketMQ 重投和 SSE 重连增加集成测试。
2. 将 SSE 订阅状态按 `runId` 隔离，并通过慢客户端和多运行并发压测。
