# 分离业务状态与 Agent 执行状态

AskMetric 包含三个可独立部署的应用：Vue Web 前端、Spring Boot 模块化单体和 Python LangGraph Runtime。Java 是工作区成员关系、对话、消息、Agent 运行、审批和审计历史的唯一事实来源；Python 只拥有执行 Checkpoint、临时上下文和评测运行。各应用通过版本化契约通信，互不读写对方的数据表，在不引入推测性 Java 微服务的前提下保持清晰的业务所有权。
