# 04 开发难点与解决方案

本文只记录项目中真实出现过或由实测暴露的问题。每个问题按“现象—根因—解决—证据—边界”复盘，面试时可以直接使用这个结构。

## 1. 数据库成功但消息未发送

**现象**：如果先写 `agent_task` 再直接发 RabbitMQ，数据库提交后进程可能崩溃，任务永久停在库中；如果先发消息再写库，Worker 又可能查不到任务。

**根因**：MySQL 事务与 Broker Confirm 是两个独立原子域。Publisher Confirm 只能证明 RabbitMQ 收到某次发送，不能覆盖“数据库提交后、发送前崩溃”的窗口。

**解决**：同一个 MySQL 本地事务写任务和 `outbox_event(PENDING)`；后台 Publisher 扫描 Outbox，发布持久消息并等待 Confirm，确认后标记 `PUBLISHED`。消费端使用至少一次语义、状态条件更新和幂等键。

**证据**：`AgentTaskService`、`OutboxPublisher`、`AgentTaskConsumer`、Outbox/Flyway 表和幂等测试。

**边界**：Outbox 解决跨库双写，不保证恰好一次；重复投递必须由业务幂等吸收。

## 2. “用了 ReAct”但没有真正 Workflow

**现象**：早期实现只有 LLM 决定下一步 Action 的思想，无法清晰回答固定节点、合法路由、暂停恢复和失败从哪里继续。

**根因**：把模型推理循环当成了工作流；状态只存在当前调用栈，缺少可持久化节点边界。

**解决**：使用 Spring AI Alibaba StateGraph 声明固定节点与条件边；LLM 只输出白名单意图或结构化约束，SAT/UNSAT、校验、新鲜度和预算由代码决定。每个节点通过 Harness 统一写 Checkpoint、预算、事件与 Trace。

**证据**：`TravelPlanningGraphFactory`、`ExplicitTravelWorkflowEngine`、图路由测试。

**取舍**：固定图降低开放性，但换来可解释、可恢复、可测试和成本上界；节点内部仍可按约束选择 RAG/Tool。

## 3. 多轮对话丢失目的地和修改意图

**现象**：助手上一轮已经说“已知你去上海”，用户回复“没有固定，随便制定”，系统又要求补充目的地；用户要求“在这个计划上修改”也可能被识别为新规划。

**根因**：意图和约束只看当前一句；会话历史、规划草稿和上一版计划没有形成显式快照，且助手文本可能被错误当成用户事实。

**解决**：按 `userId + conversationId` 隔离 Redis ChatMemory 与 PlanningDraft；创建任务时把最近会话和结构化草稿固化进 `request_json`；意图路由使用当前输入加最近对话；修改计划绑定并校验 `baseTaskId`；只继承 USER 消息和用户显式偏好。

**证据**：`AgentConversationMemoryService`、`AgentPlanningDraftService`、`TravelIntentRouterTest`、修改计划版本链。

**边界**：当前是有界窗口，不是完整 Chat History 或长期语义记忆。

## 4. 路线选择后最终计划遗漏景点

**现象**：用户选择包含外滩等多个景点的路线后，最终行程可能只围绕外滩附近地点展开；路线标题还是固定模板。

**根因**：选中 ID/名称只用于一次候选过滤，没有写入硬约束；恢复后重新搜索导致 ID 失效；“包含匹配”会把外滩金融中心等周边项当作外滩；只要命中一个候选，其他景点可能静默丢失。

**解决**：

1. 前端提交完整 `selectedAttractionNames`；
2. 抽取层写入 `TravelConstraintSpec.specificAttractions`；
3. Candidate Collector 对每个名字做精确 POI 搜索；
4. Solver 将每个选中景点设为硬约束；
5. Validator 再次检查最终文本必须覆盖全部景点；
6. 路线标题由一次批量 LLM 调用理解真实 POI 组合，失败时用真实景点名动态兜底。

**证据**：`TravelRouteSelectionServiceTest`、`TravelPlanValidatorTest`、路线恢复链路。

## 5. 异步处理期间前端“像卡死”

**现象**：用户发出消息后几秒没有任何反馈；工具与思考事件持续铺满页面；完整结果生成后才显示，使 SSE 看起来没有意义。

**根因**：前端等待第一个后端事件才进入生成态；流式事件与最终展示没有分层；早期链路只在完整生成或节点结束时通知。

**解决**：前端提交后立即显示 `Generating...`；Redis Stream 按 Token Chunk 写独立 Record，SSE 在线和重连都 `XREAD`；小字进度默认折叠为一行，正式内容到达后自动收起；最终结果仍落 MySQL。

**证据**：`AgentProgressEventStore`、`AgentTaskEventController`、SSE 压测首事件/首 Token/终态指标。

**边界**：并发排队时如果尚未写入 `QUEUED` 业务事件，首事件仍可能延迟；后续可在 API 接收后立即写排队事件。

## 6. Redis Stream、ChatMemory 和 MySQL 是否重复

**现象**：进度事件、对话和最终结果都包含相似文本，看起来像重复保存。

**根因**：把不同生命周期的数据当成同一个“记忆”。

**解决**：明确职责：Redis Stream 是 24 小时短期传输日志；Redis List 是 30 天有界会话窗口；MySQL `result_json` 是长期业务事实；Checkpoint 是节点恢复事实。事件丢失不影响最终结果，最终结果也不适合用于低延迟 Token 推送。

**面试要点**：判断是否重复要看读取方、生命周期、一致性和恢复职责，而不是看内容是否相似。

## 7. 高德能力重复、限流与地图降级

**现象**：项目曾并存本地天气/POI/路线工具与多套高德 STDIO MCP；地图规划中出现详情不可用、路线没有道路折线、短时间多 POI 调用被限流。

**根因**：重复实现导致数据口径不一致；地图详情和分段路线会放大调用量；通用 Tool QPS 太低；外部 MCP Schema 与图片能力并非恒定。

**解决**：统一使用高德托管 Streamable HTTP MCP，删除重复本地高德工具；所有远程工具进入 Tool Gateway；地图调用使用独立用户级 QPS；POI 详情和每段路线使用独立事件键；失败时降级为文本或直线点位，不让地图增强拖垮整个任务；图片只展示 MCP 实际返回的安全 URL。

**证据**：`RemoteMcpClientManager`、`TravelToolFacade`、`mapPlan`、Tool 审计和高德配置。

## 8. 可观测只能看到路径，看不到节点输入输出

**现象**：Run Explorer 最初只显示节点名称，用户无法判断“路线选择后 Agent 如何理解要求”；直接查数据库不适合作为运维产品。

**根因**：Trace 为避免隐私只保留元数据，而业务 Checkpoint 输入输出没有安全查询接口；前端路由和后端节点详情接口曾出现版本不一致导致 404。

**解决**：MySQL 保存 execution/checkpoint/tool audit；Run Explorer 先返回路径摘要，点击节点再按 checkpointId 查询 Input/Output/State；后端递归脱敏并限制深度、数组和文本；任务归属和管理员权限分别校验。

**证据**：`AgentRunQueryService`、节点详情 API、`CheckpointSnapshotSanitizerTest`。

**取舍**：完整 Prompt/Tool 参数不进入 Prometheus/Tempo，避免高基数和隐私泄露；业务回放与技术 Trace 分层。

## 9. 压测暴露任务查询读放大

**现象**：旧 `GET /agent/tasks/{id}` 在 20 请求、并发 2 时只有 0.669 req/s，成功率 95%，P95 3.743 秒。

**根因**：轮询状态却读取完整 `agent_task` 大字段，并查询全部 Workflow Checkpoint；高频轮询被实现成详情查询。

**解决**：新增 `/agent/tasks/{taskId}/status` 和 `AgentTaskStatusView`，SQL 只投影状态、当前节点、用量和时间字段；完整结果与 Checkpoint 保留在详情/Run Explorer 按需查询。压测脚本改为优先使用轻量接口。

**证据**：`AgentTaskMapper#selectStatus`、`AgentTaskController#status`、优化前容量报告。

**边界**：轻量接口已提交，但优化后 QPS 尚未形成正式复测报告，不能提前写提升倍数。

## 10. Agent 并发提高但吞吐不增长

**现象**：真实任务从并发 1 提升到 2，吞吐只从 2.37 提高到 2.59 task/min，首 Token P95 却从 5.17 秒恶化到 27.75 秒。

**根因判断**：首事件先恶化说明主要发生排队；生产默认 Worker `concurrency=1/prefetch=1`，短突发的动态消费者没有及时产生收益。黑盒测试尚不能完全排除模型、地图或数据库瓶颈。

**处理**：在并发 2 主动停止，不继续用并发 4 消耗正式环境；建议先连接 Prometheus 验证 queue delay、ready/unacked、节点和模型耗时，再 A/B 测试固定 Worker concurrency 1/2。

**面试要点**：压测目标不是得到最大的数字，而是找到吞吐拐点和停止条件。

## 11. 上下文压缩第一次反而膨胀

**现象**：首次离线实验把标题、旧历史和新结构同时写入 Envelope，1500 字符膨胀到约 2397，比例 1.598。

**根因**：忽略 JSON/标签结构开销，没有短会话阈值，也没有定义硬约束和近期轮次优先级。

**解决**：规范化硬约束、缩短标签、对旧历史生成极短摘要并完整保留最近轮次；冻结 10 个样例复测后从 1500 字符降至 990，字符比 0.660，硬约束和最近轮次保持率均为 100%。

**边界**：这是字符级离线原型，不是 Qwen Tokenizer 结果，也没有进入生产上下文模块，不能写成“线上 Token 降低 34%”。

## 12. RAG 双索引的一致性

**现象**：社区内容写库成功但 ES/PGVector 失败，或旧消息晚到覆盖新版本，会造成搜索和事实表不一致。

**根因**：事务数据库和两个外部索引不能放进可靠的同步强事务；消息至少一次且可能乱序。

**解决**：MySQL 事实表和知识 Outbox 同事务；Knowledge Worker 使用 `sourceType + sourceId + contentVersion` 幂等；旧版本拒绝覆盖；删除使用 Tombstone；定时对账和 DLQ 重放；全量重建完成后切换 ES Alias。

**取舍**：接受最终一致性，换取主业务不被索引故障拖垮；查询单路失败时降级并显式标记。

## 13. 如何在面试中复盘难点

建议使用统一结构：

1. 先给可观测现象或失败样例；
2. 说明为什么直觉方案不成立；
3. 画出数据/消息/状态边界；
4. 解释最终实现与幂等、恢复、降级策略；
5. 给测试、指标或代码证据；
6. 主动说明仍未验证的边界。

最有价值的三个故事是：Outbox 解决双写、路线选择景点不丢失、压测发现读放大与 Worker 排队。它们分别体现分布式可靠性、Agent 业务正确性和性能工程能力。
