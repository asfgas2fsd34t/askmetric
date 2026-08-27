# 使用 Schema-first JSON 事件

`contracts/` 下版本化的 JSON Schema 和 AsyncAPI 定义是 Java-Python 消息的事实来源，两端都执行校验和契约测试。选择该方案而不是重复 DTO、共享数据表或 Protobuf，是为了在明确表达兼容性的同时保持队列流量可检查；前端 HTTP Client 单独根据 Java OpenAPI 生成。
