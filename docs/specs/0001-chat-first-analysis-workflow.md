# Spec: AskMetric A 方案 Chat-first 可审计分析工作流

## Problem Statement

业务用户希望通过自然语言了解经营指标，但企业 BI 场景不能把每一条消息都误认为分析任务，也不能把模型生成的答案、SQL 或副作用操作当作可信事实。一次对话可能包含普通交流、多个分析目标、口径澄清、任务控制和审批；同一个分析目标又可能跨越多条消息和多次 Agent 运行。

如果 Conversation、Agent Run 和 Analysis Task 没有明确边界，系统会出现虚假的任务、不可恢复的运行、重复执行操作、无法解释的结论以及无法重现的报告。用户也需要知道 Agent 当前在做什么、使用了哪些证据、哪里需要自己决定，以及最终报告是否可以脱离系统重新查看。

## Solution

交付 AskMetric 的 A 方案「三栏协作台」作为首版 chat-first 工作区。对话区是主要工作面，左侧提供 Conversation 导航，右侧展示当前分析上下文、任务阶段、证据、发现和审批提案。

每条用户消息都创建一次 Agent Run。Agent 通过意图路由判断这次运行是普通对话、分析、任务控制还是审批。普通问候只产生对话回复，不创建 Analysis Task；需要分析时才创建或继续一个持久任务。一个 Conversation 可以包含多个 Analysis Task，但同一时刻最多有一个活动任务。

分析任务由 Python LangGraph Runtime 推进，但 Java 是业务状态、权限、审批、证据快照、报告和审计记录的唯一事实来源。Java 通过 Transactional Outbox 和 RocketMQ 5 投递 Agent 工作，Python 通过版本化事件返回进度和结果。Python 永远不能直接访问数据源凭据或执行副作用。

分析结果必须是通过 Schema 校验的结构化可审计分析。Java 根据该结构和受策略限制的证据快照，确定性渲染出可查看、可下载、无需再次运行 Agent 的独立 HTML 分析报告。

## User Stories

1. As a 业务用户, I want to 在 A 方案工作区直接输入自然语言, so that 我不需要编写 SQL 就能开始分析。
2. As a 业务用户, I want to 询问“你好，你是谁”这类普通问题, so that 我可以了解产品而不会产生无意义的 Analysis Task。
3. As a 业务用户, I want to 看到普通消息对应的 Agent Run 已完成, so that 我知道系统处理了消息但没有误把它当成分析任务。
4. As a 业务用户, I want to 在同一 Conversation 中连续询问多个问题, so that 我不必为每个问题重新打开工作区。
5. As a 业务用户, I want to 在一个 Conversation 中拥有多个 Analysis Task, so that 不同经营目标可以保留在同一个上下文中。
6. As a 业务用户, I want to 看到当前 Conversation 是否有活动任务, so that 我不会误以为普通对话正在运行分析。
7. As a 业务用户, I want to 输入“为什么本月 MRR 下降”这类分析问题, so that 系统能创建一个有明确目标的 Analysis Task。
8. As a 业务用户, I want to 看到本次消息被路由为 chat、analysis、task_control 或 approval, so that 我能理解系统如何解释我的意图。
9. As a 业务用户, I want to 在低置信度或任务目标冲突时被询问, so that 系统不会静默猜测错误的分析目标。
10. As a 业务用户, I want to 看到当前任务的目标、状态、标识和已完成运行数量, so that 我可以判断分析是否仍在推进。
11. As a 业务用户, I want to 看到分析阶段按“理解问题、检索口径、执行查询、验证证据、生成报告”推进, so that 复杂分析过程是可观察的。
12. As a 业务用户, I want to 在指标定义存在歧义时确认口径, so that MRR 等指标的结论建立在共同认可的定义上。
13. As a 业务用户, I want to 继续回答口径问题而不创建新的 Analysis Task, so that 澄清消息会推进原任务。
14. As a 业务用户, I want to 在需要时提供自定义指标定义, so that 系统可以在明确边界后继续当前分析。
15. As a 业务用户, I want to 看到分析计划的主要步骤, so that 我能在查询开始前发现目标理解错误。
16. As a 业务用户, I want to 看到指标定义、知识来源和数据证据的引用, so that 我可以检查结论来自哪里。
17. As a 业务用户, I want to 知道哪些字段被脱敏、聚合或限制, so that 敏感数据不会以不可见的方式泄露。
18. As a 业务用户, I want to 看到查询正在由受治理的数据访问路径执行, so that Python Agent 不会绕过工作区权限访问数据源。
19. As a 业务用户, I want to 在 Agent 运行期间看到进度事件, so that 页面不会像无响应一样等待模型完成。
20. As a 业务用户, I want to 停止一个正在运行的分析, so that 我可以及时终止错误、过慢或不再需要的任务。
21. As a 业务用户, I want to 停止后保留已经产生的消息和审计事件, so that 我能知道任务在哪里被取消且不会丢失上下文。
22. As a 业务用户, I want to 在网络断开后重新连接并恢复时间线, so that 我不需要重复发起可能已经执行过的分析。
23. As a 业务用户, I want to 看到 Agent 运行失败所处的阶段和原因, so that 我能区分澄清失败、查询失败、证据不足和报告失败。
24. As a 业务用户, I want to 在证据不足时得到明确的不确定性说明, so that 系统不会用流畅的文字掩盖无法验证的结论。
25. As a 业务用户, I want to 看到分析发现和关键指标摘要, so that 我可以快速判断结果是否回答了原始目标。
26. As a 业务用户, I want to 看到建议的后续任务及其确切参数, so that 我能在产生外部影响前检查对象、负责人、截止日期和幂等键。
27. As a 业务用户, I want to 知道一个操作提案需要人工审批, so that Agent 不能在我不知情时创建外部工作项。
28. As a 有审批权限的工作区成员, I want to 批准一个确切且不可变的操作提案, so that 只有我明确授权的参数可以被执行。
29. As a 有审批权限的工作区成员, I want to 拒绝操作提案, so that 被拒绝的提案不会产生任何副作用。
30. As a 工作区管理员, I want to 根据工作区策略要求另一名成员审批, so that 高风险操作不会由发起者单独授权。
31. As a 业务用户, I want to 看到已批准操作的执行结果和审计记录, so that 我能确认后续任务是否只创建了一次。
32. As a 业务用户, I want to 在审批前提案发生变化时使原审批失效, so that 审批不会被重用于不同参数的操作。
33. As a 业务用户, I want to 在分析完成后查看独立 HTML 报告, so that 报告不依赖在线 Agent 或当前 Conversation 才能打开。
34. As a 业务用户, I want to 下载 HTML 报告, so that 我可以在本地归档、评审或分享受控的分析快照。
35. As a 业务用户, I want to 报告包含结论、指标时间定义、证据、引用、假设和不确定性, so that 报告本身足够可审计。
36. As a 业务用户, I want to 报告标明数据截至时间、指标版本和报告标识, so that 我知道报告对应的事实版本。
37. As a 业务用户, I want to 重新分析时生成新版本并关联旧报告, so that 历史报告不会被静默修改。
38. As a 数据分析师, I want to 管理工作区指标定义和语义目录版本, so that Agent 使用的业务口径可被治理和追溯。
39. As a 数据分析师, I want to 检查查询、结果行数、耗时和证据快照, so that 我可以复核分析是否符合数据规则和预算。
40. As a 工作区管理员, I want to 让 Java 依据当前 Workspace Membership 授权每个操作, so that 客户端提供的 workspace 标识不能越权。
41. As a 工作区管理员, I want to 让不同工作区的数据和 Conversation 隔离, so that 一个工作区的分析结果不会出现在另一个工作区。
42. As a 平台维护者, I want to 看到同一 Trace 关联 Web、Java、RocketMQ、Python、查询、MCP 和报告渲染, so that 我可以定位端到端延迟和失败。
43. As a 平台维护者, I want to 让重复或乱序事件不会重复推进状态, so that at-least-once 投递不会造成重复运行或重复副作用。
44. As a 平台维护者, I want to 在没有模型凭据时运行确定性分析测试, so that CI 能验证核心状态和安全边界。

## Implementation Decisions

- A 方案「三栏协作台」是首版主交互。左侧是 Conversation 导航，中间是消息和输入区，右侧是分析上下文。B「报告优先画布」和 C「任务时间线」保留在原型中作为交互对比，不作为首版生产页面。
- Conversation 是用户可见消息线程，可以包含零个或多个 Analysis Task。Analysis Task 表示一个持久分析目标，不能被 Message 或 Agent Run 替代。同一 Conversation 最多一个活动任务，不同 Conversation 可以并行运行。
- 每条用户 Message 创建一个 Agent Run。Agent Run 必须持久化意图路由、输入、时间和按序审计事件；运行结果由事件流推导，不保存重复的状态快照。T08 引入 Analysis Task 后，Run 通过 `analysisTaskId` 记录实际任务关联；任务创建、继续或切换由该关联及任务事件推导。
- Intent Route 的一级类型为 `chat`、`analysis`、`task_control` 和 `approval`。明确命令使用确定性规则；低置信度或关系冲突必须转为澄清，不允许静默创建或切换任务。
- 同一 Conversation 切换分析目标时，原活动 Analysis Task 变为 `waiting_for_input`，新 Analysis Task 成为唯一 `active` 任务，并持久化记录旧任务、触发 Agent Run 与新任务关联的切换事件。用户以明确的继续命令补充当前目标时，新的 Agent Run 关联原任务且不会创建第二个任务；没有活动任务时，继续或切换命令必须请求澄清。
- Analysis Task 的首版状态为 `active`、`waiting_for_input`、`waiting_for_approval`、`completed`、`failed` 和 `cancelled`。Agent Run 的进度和结果由 Java 持久化的有序事件表达，并拒绝非法事件流转。
- A 方案的交互状态来自原型：`idle -> clarification -> planning -> retrieving -> querying -> synthesizing -> approval -> completed|cancelled|failed`。`approval` 只表示等待确切提案的人工决定，不表示 Agent 可以自行执行。
- Java Spring Boot 模块化单体拥有 User、Workspace Membership、Workspace Policy、Conversation、Message、Agent Run、Analysis Task、Approval、Report、Audit Record、Data Connection、Metric Definition、Evidence Snapshot 等业务状态。
- Python LangGraph Runtime 只拥有 Checkpoint、临时 Context Pack、派生 RAG 索引和评测执行状态。Python 不读写 Java 业务表，不接收 Data Connection Secret，不直接连接分析数据源，不执行副作用。
- Java 与 Python 之间使用 `contracts/` 下版本化 JSON Schema 和 AsyncAPI 作为事实来源。T01 事件信封包含 eventId、schemaVersion、runId 和序号；两端都执行 Schema 校验和契约测试。链路追踪字段（traceId、spanId）在接入 OpenTelemetry 时通过新契约版本增加。
- Java 在事务中保存 Agent Run 和 Transactional Outbox 记录，再发布到 RocketMQ 5。Python 消费工作后返回结构化进度、结果或失败事件；Java 先将事件持久化到 `agent_run_event`，再同步 SSE 投影，消息采用 at-least-once 语义，消费者先去重再修改状态，有限重试后进入死信队列。Conversation 消息提交支持按用户、Workspace 和 Conversation 作用域的 `Idempotency-Key`，重复请求返回首次响应，同一 key 搭配不同内容拒绝且不创建新的业务记录。直接创建 Agent Run 的 T01 探针入口已移除，业务路径统一使用数据库 Agent Run 和 Outbox。
- 首版外部行为入口是 Conversation REST/SSE seam。REST 负责提交消息、停止运行、审批提案和获取报告；SSE 负责按聚合序号推送消息、Agent Run、Analysis Task、证据、提案、报告和失败事件，并支持断线后的重放。
- Vue 只保存临时展示状态，依据 Java 返回的事件和快照渲染 A 方案。页面必须区分 Conversation 标题、Agent Run 状态、Analysis Task 状态、当前阶段、证据、审批和报告，不把客户端状态当作业务事实。
- Python 通过 Context Pack 获得当前消息、任务状态、相关历史、已确认事实、Metric Definition、检索证据、策略和剩余预算。对话摘要是有范围的派生输入，不能替代原始 Message；User Memory 只有经确认后才可使用。
- 受治理查询由 Java Query Gateway 执行。Java 使用 SQL AST 限制单条 SELECT 或只读 CTE，校验语义目录、Workspace Membership、Data Classification、查询超时、行数、字节数和成本预算，并保存查询审计和 Evidence Snapshot。
- RAG 来源只作为不可信 Knowledge Source 使用。引用内容不能改变 Workspace Policy、权限、工具调用或 Approval 规则；Restricted 数据不能进入 Python 或报告，Sensitive 数据按策略脱敏或聚合。
- 可审计分析使用结构化 Schema，包含结论、指标和时间定义、证据、表格、受限图表模型、引用、不确定性、假设、建议和可选 Action Proposal。模型不能直接提供任意 HTML、JavaScript 或图表脚本。
- Java Report 模块从结构化分析和 Evidence Snapshot 确定性渲染独立、不可变 HTML。报告保存分析版本、指标版本、数据截至时间、来源、渲染版本和 supersedesReportId；查看和下载都产生 Audit Record。
- Action Proposal 保存确切操作类型、参数、发起 Agent Run、Workspace Policy 版本和幂等键。Java Approval 模块校验当前成员权限和分离审批人规则；参数变化会使原提案和审批失效。
- 只有 Java Tool Gateway 可以调用 Sandbox Work Tracker MCP。调用必须绑定已批准的确切提案和幂等键，并保存请求、响应、结果和 Audit Record。Python 只能提出操作，不能执行操作。
- 首版使用确定性的 B2B SaaS Demo Workspace、PostgreSQL Demo Warehouse 和 MRR 下降参考场景，确保没有模型凭据时仍能跑通测试。演示数据为合成数据，公开演示禁止外部连接和匿名报告分享。
- 接入 OpenTelemetry 后，运行和查询的 Trace 使用 traceId 关联浏览器请求、Java、RocketMQ、Python、模型、Query Gateway、MCP 和 Report Renderer；Java 额外保存模型、Schema 版本、Token、成本、延迟和业务结果。该能力不属于 T01。

## Testing Decisions

- 最高测试 seam 是 Conversation REST/SSE API。测试以提交用户 Message 开始，通过真实的 Docker Compose 运行 Java、Python、RocketMQ 和 PostgreSQL，消费 SSE 直到终态，只断言外部可观察的消息、事件、状态、权限、证据、审批和报告行为。
- 该 seam 必须覆盖同一 Conversation 中的 chat、new analysis、continue analysis、switch task、task_control、approval 和 reconnect。测试不得依赖 Vue 组件内部状态或 Python 节点实现细节。
- 必须有一条确定性的端到端验收场景：问候只创建 Agent Run；MRR 问题创建 Analysis Task；口径回复继续该任务；任务经过检索、受治理查询和证据验证；产生 Action Proposal；批准后只创建一次后续任务；生成独立 HTML 报告。
- 必须有停止和恢复场景：运行中发送停止后状态为 cancelled，已完成事件保留；断开 SSE 后用最后序号重连，不能丢事件、重复消息或重新执行已批准操作；Python 进程重启后从 Checkpoint 恢复。
- 必须有失败场景：低置信度意图、无效指标口径、越权工作区、非法 SQL、超预算查询、证据不足、模型超时、RocketMQ 重试/死信、报告 Schema 无效和 MCP 失败都要显示阶段化 Analysis Failure，不得生成无依据的成功结论。
- 必须有审批安全场景：未经授权成员不能审批；需要分离审批人时发起者不能自批；提案参数变化、重复审批、拒绝、重放和超时都不能产生副作用；同一个幂等键最多创建一个后续任务。
- 必须有多租户场景：不同 Workspace 的 Conversation、Metric Definition、Knowledge Source、Evidence Snapshot、Report 和 Audit Record 互不可见；客户端伪造 workspace 标识不能改变授权结果。
- 必须有契约测试：Java 和 Python 对所有版本化事件执行 JSON Schema 校验，拒绝不兼容 schemaVersion，验证序号、去重和乱序处理，并检查事件可被 SSE 重放。
- 必须有查询治理测试：只允许语义目录中的字段和只读语句，拒绝写入、注入、未登记表、Restricted 字段和超预算查询；Evidence Snapshot 只包含策略允许且实际使用的结果。
- 必须有报告测试：相同结构化分析、证据快照和模板版本逐字节生成相同 HTML；报告不依赖模型、不执行脚本、不包含受限数据；查看和下载分别生成审计记录。
- 必须有 Vue A 方案 Smoke Test：验证三栏布局、消息流、任务阶段、证据、审批控件、停止、重置、主题和报告入口与 SSE 状态一致；不测试 CSS 具体实现或组件私有方法。
- 必须有 RocketMQ、LangGraph Checkpoint 和确定性 HTML 的第一周技术探针，每个探针都能在 CI 和开发机运行并有独立 Smoke Check。若 RocketMQ Python 客户端兼容性不可靠，必须在业务功能开始前重新评估传输方案。
- 测试优先使用合成 Demo Warehouse 和固定时间，避免依赖外部模型、外部数据源或网络。模型相关能力通过固定响应适配器和离线评测用例验证，叙述质量另由固定版本 Judge 评估。
- 当前仓库尚无生产实现测试先例；测试约定以已有 ADR、领域词汇和 contracts 的 Schema-first 设计为先例，并在实现阶段把第一条端到端场景作为回归基线。

## Out of Scope

- 通用助手能力和与 BI 分析无关的开放式任务。
- 自动理解任意数据库、多数据库连接器和可视化语义建模器。
- Dashboard 设计器、实时大屏和复杂拖拽报表编辑器。
- Runtime Multi-Agent 讨论、多模型自动路由和动态创建专家 Agent。
- Python 直接访问企业数据源、保存数据连接凭据或执行任何外部副作用。
- 自动写回源业务系统；首版只支持一个 Sandbox Work Tracker MCP 的 `create_follow_up_task` 操作。
- OCR、通用企业搜索、图片和扫描件知识摄取。
- 匿名报告分享、公开上传外部文件和连接真实客户系统。
- Kubernetes、多地域高可用、零停机部署和生产级多租户计费。
- 任意模型生成 HTML、JavaScript、ECharts Option 或未经 Schema 校验的图表。
- 在本 Spec 中实现 B、C 两个备选布局的生产化；它们只用于原型比较和后续评估。

## Further Notes

- 该 Spec 对应求职展示项目的第一条可运行纵向链路，预计在 2 至 3 个月开发周期内优先完成状态边界、可靠调度、受治理查询、审批和可重现报告。
- 推荐实施顺序为：第一周完成三项技术探针；第二周建立 Vue、Spring Boot、Python、contracts 和 Docker Compose 骨架；随后先完成 Conversation REST/SSE seam 的确定性端到端链路，再扩展知识、记忆、评测和运维。
- 原型文件中的 A 方案是交互依据，但不是生产组件实现。生产实现必须保留原型表达的领域边界：每条消息一个 Agent Run、任务由 Agent 判断是否创建、一个 Conversation 可有多个任务，以及任何副作用都必须经过 Java 审批。
- 首版完成的最低标准是：10 个核心分析用例至少 8 个产生正确且带引用的结果；支持多轮澄清、中断、审批和恢复；Java、Python、Vue 都有不可替代行为；没有模型凭据也能运行确定性测试；公开 Demo、Trace、评测结果、HTML 报告和已知失败可被检查。
