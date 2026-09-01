# 通过 RocketMQ 投递 Agent 工作

需要 Python 执行的 Agent Run 由 Java 持久化，并通过 Transactional Outbox 将消息发布到 RocketMQ 5.x；Python 使用 Apache 当前的 gRPC Python Client 消费工作，再返回进度和结果事件，由 Java 持久化并流式推送给客户端。消息采用 at-least-once 语义：版本化事件信封携带稳定标识、关联信息和聚合序号，消费者在修改状态前先去重。

首版无模型凭据的确定性普通聊天不需要 Python 执行或外部副作用，因此由 Java 在同一业务事务中持久化 Message、Agent Run 与审计事件后直接完成。它不通过 RocketMQ，也不适用于 Analysis Task；后续分析、恢复、重试和取消仍遵循 Outbox/RocketMQ 路径。选择 RocketMQ 而不是 RabbitMQ、Kafka、同步 HTTP 或 Temporal，是为了匹配目标 Java 企业技术栈，同时保留持久队列、重试和死信能力；系统不宣称 exactly-once，跨语言客户端兼容性必须在早期可执行技术探针中通过验证。
