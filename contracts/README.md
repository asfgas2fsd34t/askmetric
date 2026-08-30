# AskMetric 事件契约

T01 使用两个版本化 JSON Schema 证明 Java 与 Python 的 Agent Run 双向通信：

- `events/agent-run-request.v1.json`：Java 发布到 `askmetric-agent-run-request`。
- `events/agent-run-event.v1.json`：Python 发布到 `askmetric-agent-run-event`，Java 消费后通过 SSE 推送。

事件信封中的 `runId` 标识一次 Agent Run，`sequence` 从 1 开始严格递增。消费者应先按 `eventId` 去重，再按 `sequence` 追加事件。T01 暂不携带链路追踪 ID，后续接入 OpenTelemetry 时通过新契约版本增加 `traceId`/`spanId`。T01 的内存存储只用于探针，后续票据会替换为持久化实现。
