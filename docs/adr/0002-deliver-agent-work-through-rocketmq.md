# 通过 RocketMQ 投递 Agent 工作

Java 持久化每次 Agent 运行，并通过 Transactional Outbox 将消息发布到 RocketMQ 5.x；Python 使用 Apache 当前的 gRPC Python Client 消费工作，再返回进度和结果事件，由 Java 持久化并流式推送给客户端。消息采用 at-least-once 语义：版本化事件信封携带稳定标识、关联信息和聚合序号，消费者在修改状态前先去重。选择 RocketMQ 而不是 RabbitMQ、Kafka、同步 HTTP 或 Temporal，是为了匹配目标 Java 企业技术栈，同时保留持久队列、重试和死信能力；系统不宣称 exactly-once，跨语言客户端兼容性必须在早期可执行技术探针中通过验证。
