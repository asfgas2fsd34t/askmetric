# 通过 Java 执行副作用操作

Python 可以选择工具并提交不可变的操作提案，但只有 Java Tool Gateway 可以通过 MCP 执行已批准的副作用。Java 校验当前用户能否批准这些确切参数，将执行绑定到幂等键并记录结果，从而防止 Agent Runtime 或不可信知识来源绕过人工审批。
