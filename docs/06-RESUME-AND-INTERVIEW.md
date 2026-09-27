# 06 简历与面试手册

## 1. 简历项目版本

### 项目名称

**行程智控——Agent 旅行决策与智能规划平台**

### 项目描述

面向多轮旅行咨询与复杂行程决策的异步 Agent 平台。系统以声明式 StateGraph 和自研 Harness 编排意图识别、约束抽取、混合检索、POI/路线工具、确定性求解、流式生成与结果校验；通过 MySQL Checkpoint、Transactional Outbox、RabbitMQ 和 Redis Stream 支持任务恢复、可靠投递及 SSE 断线续传，并提供多用户、多会话隔离与 Agent 全链路可观测能力。

### 技术栈

Java 21、Spring Boot、Spring AI、Spring AI Alibaba Graph、WebFlux/Reactor、MyBatis-Plus、MySQL、Redis/Redis Stream、RabbitMQ Quorum Queue、Elasticsearch、PGVector、RRF、Sentinel、Redisson、OpenTelemetry、Micrometer、Prometheus、Grafana、Tempo、Docker Compose、Kubernetes、Helm。

### 推荐职责

1. 设计异步 Agent 任务平台，以 `Idempotency-Key + MySQL 唯一索引` 保证提交幂等，在同一本地事务写入任务与 Transactional Outbox；结合 RabbitMQ Publisher Confirm、持久消息、手动 ACK、延迟重试和 DLQ，解决数据库与消息发布的双写可靠性问题。
2. 将旅行规划实现为固定 StateGraph，拆分意图路由、约束抽取、RAG、候选检索、路线选择、确定性求解、地图规划、流式生成、规则校验和时效复查；自研 Harness 提供节点超时/重试、预算、Checkpoint、暂停追问和故障续跑。
3. 基于 Redis Stream 保存节点进度与模型 Token Chunk，以 SSE + `Last-Event-ID` 支持实时输出和断线续读；最终结果落 MySQL，明确区分事件传输、短期对话记忆、节点恢复和最终业务事实。
4. 建设 Elasticsearch BM25 + PGVector 语义召回 + RRF 融合的混合检索链路，通过版本化知识事件完成社区内容的质量过滤、PII 脱敏、切片、增量索引、乱序幂等、对账、DLQ 重放和蓝绿重建。
5. 构建统一 Tool/MCP Gateway，接入高德 MCP 的 POI、详情、天气与多交通路线，集中实现权限、Schema、超时、隔离线程池、幂等重试、Redis 缓存、Redisson 防击穿、Sentinel 限流熔断和 MySQL 审计。
6. 建立 Metrics/Trace/Business Run 三层可观测体系：Prometheus/Grafana 观察队列、节点、首 Token、RAG 与 Tool，OpenTelemetry/Tempo 串联 HTTP/Outbox/MQ/Worker，并通过 Run Explorer 按 taskId 回放脱敏节点输入输出。
7. 建立异步 Agent 分层压测方法，区分接入 req/s、RAG query/s 与 Agent task/min；受控实测幂等热路径 69.7 req/s、RAG 16.3 query/s、单 Worker 完整规划约 2.4 task/min，并通过并发阶梯发现 Worker 排队和任务查询读放大问题。

### 可选替换职责

- 实现用户注册登录和 Redis Spring Session，使用 `userId + conversationId` 完成任务、短期记忆、PlanningDraft 和会话双层隔离，只跨会话共享公共知识与显式白名单偏好。
- 设计景点路线选择门：未指定景点时生成多条真实 POI 路线并动态命名，用户选择后把全部景点转为硬约束并逐一核验，解决恢复后景点静默遗漏与同名周边 POI 误匹配。
- 将同一镜像拆成 Agent API、Agent Worker、Knowledge Worker 三种运行角色，提供 Compose 与 Helm 配置、探针、优雅停机、HPA/PDB 和可选 KEDA；真实多节点 HA 仍待演练。

## 2. 30 秒项目介绍

这是一个把大模型能力放入可靠异步任务系统的旅行规划 Agent。请求进入后先在同一事务持久化任务和 Outbox，再由 RabbitMQ 驱动 Worker 执行固定 StateGraph；流程通过混合 RAG 和高德 MCP 获取知识与实时 POI，用确定性求解器约束预算和必选景点，最后由模型流式生成并做规则校验。执行过程使用 MySQL Checkpoint 恢复、Redis Stream/SSE 推送，并用 Prometheus、Tempo 和 Run Explorer 分别解决聚合监控、技术调用链和业务路径回放。

## 3. 三分钟展开顺序

1. 先说为什么采用异步任务，而不是 HTTP 等待模型；
2. 解释 Outbox + RabbitMQ + 幂等消费的可靠性；
3. 画固定 StateGraph，强调 LLM 与确定性组件的职责；
4. 解释 WorkflowState、Checkpoint 和 supplementalVersion；
5. 说明记忆、Redis Stream 与 MySQL 结果为何不重复；
6. 介绍 Hybrid RAG 与 Tool Gateway；
7. 用压测数据讲吞吐拐点、读放大和优化；
8. 主动说明未完成的 HA、长期记忆和正式 SLA 边界。

## 4. 能力证据矩阵

| 能力 | 代码/配置 | 自动化/报告 | 结论 |
|---|---|---|---|
| 幂等任务 + Outbox | `AgentTaskService`、`OutboxPublisher` | `AgentTaskServiceTest` | 已实现、单测与真实幂等压测通过 |
| Rabbit 重试/DLQ/Quorum | `AgentRabbitConfiguration`、Consumer | 队列参数测试 | 已实现；多节点故障待演练 |
| 固定 StateGraph | GraphFactory、WorkflowEngine、NodeCatalog | 图与路由测试 | 已实现 |
| 结构化约束/SAT | ConstraintSpec、Z3/JVM Solver | 抽取/求解测试 | 已实现；Z3 容器全链路待验证 |
| Checkpoint/恢复 | task/checkpoint/service | Engine/Service 测试 | 已实现；恢复 RTO 未测 |
| Redis Stream/SSE | EventStore、EventController | 真实 SSE 冒烟/容量测试 | 已实现；最大连接数未测 |
| 多用户/会话隔离 | Session、Conversation、Memory | Service 测试 | 已实现 |
| Hybrid RAG/RRF | KnowledgeHybridSearchService | 单测、数据集、消融脚本 | 已实现；生产质量消融待执行 |
| 知识闭环 | IndexPipeline、Outbox/Consumer | Pipeline 测试 | 已实现 |
| Tool/MCP 治理 | ToolGateway、Sentinel、Redisson | Gateway/MCP 测试 | 已实现 |
| 高德地图 | RemoteMcp、mapPlan、前端地图 | 连接测试可选 | 已实现；受外部 Schema/配额约束 |
| 可观测 | PlatformObservability、OTel 配置 | 指标测试、Run Explorer | 已实现 |
| Compose/Helm | 三角色、Chart | 静态配置/Runbook | 已配置；真实 K8s 发布待执行 |
| 压测 | phase6_load.py | 2026-09-27 容量报告 | 有真实数据但样本较小 |
| 上下文压缩 | 离线实验 | 10 样例字符实验 | 原型完成，未接生产 |
| RPO/RTO | Runbook | 无正式演练 | NOT_MEASURED |

## 5. 技术选型高频问答

### 为什么异步，不同步等待模型？

规划包含模型、RAG、地图和用户追问，耗时不可预测。异步任务可以快速返回 taskId、削峰、独立扩 Worker、持久化状态并暂停恢复；SSE 保留实时体验。

### 为什么 Outbox，Publisher Confirm 不够吗？

Confirm 只能确认 Broker 收到一次发送，不能覆盖数据库提交后、发送前崩溃。Outbox 把任务和待发事件放进同一事务，Confirm 后再更新发布状态；系统接受至少一次投递，用幂等和状态机消除重复。

### 为什么 Quorum Queue 不能替代 Outbox？

Quorum 解决消息进入 RabbitMQ 后的复制和选主；Outbox 解决数据库到 Broker 之间的可靠衔接。当前 Compose 是单 Rabbit 节点，不能借 Quorum 类型宣称多节点 HA。

### 为什么进度用 Redis Stream，不用 Pub/Sub？

Pub/Sub 不保留离线消息；Stream 有有序 Record ID，可以从 `Last-Event-ID` 后继续读。Stream 只有短期传输职责，最终结果仍在 MySQL。

### 为什么不用 WebSocket？

业务主要是服务端单向推送节点进度和 Token，用户补充走普通 HTTP。SSE 协议更简单，有事件 ID 和浏览器重连语义。

### MySQL 结果、ChatMemory、Checkpoint 和 Stream 是否重复？

不重复：结果是长期业务事实，ChatMemory 是有 TTL 的下一轮语境，Checkpoint 是节点恢复事实，Stream 是短期传输事件。读取方、生命周期和一致性要求不同。

### Harness 与 Harness.io 是一回事吗？

不是。项目 Harness 是 Agent 执行外壳，负责预算、超时/重试、Checkpoint、恢复和可观测；Harness.io 是 CI/CD 产品。

### 为什么 ES + PGVector + RRF？

BM25 擅长地名和精确词，向量擅长语义近似；两路分数不在同一尺度，所以使用基于名次的 RRF。任一路故障还能单路降级。

### 为什么不使用 Nacos 和 Seata？

当前服务发现由 K8s Service/DNS 提供，Worker 通过 MQ 解耦，没有复杂 RPC 注册需求。跨 MQ/索引/外部 API 不适合长分布式事务，使用本地事务、Outbox、幂等和补偿边界更清晰。

### Sentinel 和 Redisson 为什么同时存在？

Sentinel 做用户 QPS、并发隔离和熔断；Redisson 只做跨实例缓存防击穿等必要互斥。业务正确性依赖数据库状态机，不依赖分布式锁包住慢模型调用。

## 6. Workflow 与上下文追问

### WorkflowState 和 Checkpoint 的区别？

WorkflowState 是执行时把 request、多个节点输出和 metrics 合并出的内存视图；Checkpoint 是 MySQL 中某个节点某次 attempt 的事实快照。恢复时重新创建 State，再合并成功 Checkpoint。

### 用户补充后为什么不能复用所有旧节点？

目的地、预算或路线变化会让旧候选和求解结果失效。系统把 `_supplementalVersion` 写入物理节点 ID，新版本重跑受影响节点，同时保留已经消耗的模型/Token/节点预算。

### 工具调用是不是固定工作流？

固定的是业务阶段和合法路由，不是每次都执行所有工具。节点根据约束决定查哪些 POI、是否需要路线、选哪种交通；所有调用通过统一 Gateway。

### 当前上下文压缩做到什么程度？

已有限长会话、TopK、结构化 Tool 裁剪、上一版计划限长和预算；没有生产级 tokenizer 分区与滚动摘要。离线字符实验减少 34% 不能写成线上 Token 降低 34%。

### 如何保证用户和会话不串？

所有任务和会话校验 userId；短期记忆与 PlanningDraft 使用 userId + conversationId 哈希；不同会话只共享公共知识和用户显式保存偏好。Run Explorer 同样校验归属。

## 7. 压测追问

### 为什么不说系统支持多少统一 QPS？

异步系统接入和完成不在一个时间尺度。POST 返回 202 只代表接住任务；必须分别报告接入 req/s、RAG query/s、Agent task/min、首 Token 和 E2E。

### 69.7 QPS 能否理解为每秒创建 69 个任务？

不能。它是相同 Idempotency-Key 的热路径，50 次全部返回 taskId=47，只创建一个任务。它证明幂等接入，不证明不同任务的创建或完成能力。

### Agent 为什么只有 0.04 task/s？

单次完整规划平均约 25 秒，Worker 起始并发为 1，理论吞吐 `1/25≈0.04 task/s`，与实测一致。优化应聚焦排队、节点耗时、外部配额和 Worker 扩展，不是单纯加 HTTP 线程。

### 为什么并发 2 反而体验变差？

吞吐只提升约 9%，首 Token 从 5.17 秒变为 27.75 秒；首事件先变慢说明主要是排队。默认单消费者是重要嫌疑，但最终仍需 queue delay、Rabbit ready/unacked、节点和模型指标归因。

### P95 是否可信？

Agent 每档只有 2～3 个样本，P95 接近最慢值，只能称本轮观测 P95。它足以发现拐点，不能承诺 SLA。

### RAG 16.3 QPS 是否牺牲质量？

本轮没有同轮冻结索引做质量消融，因此不能保证。正式结论必须同时报告 Recall@K、MRR/NDCG、无答案和引用正确率。

### 能支持多少用户？

不能从 task/s 直接换算 DAU。若保守准入 1.2 task/min，且每个持续活跃用户平均 10 分钟发起一次完整规划，可近似支持 12 个持续活跃规划用户；浏览结果的用户不消耗相同 Agent 容量。回答必须带业务频率假设。

## 8. 真实难点故事

### 故事一：路线选择后丢景点

从“选中 ID 只过滤一次”追到恢复后 ID 失效和包含匹配误命中，最终用完整名称硬约束、逐名检索、Solver 与 Validator 四层闭环解决。这个故事体现 Agent 业务正确性。

### 故事二：压测发现查询读放大

状态查询在 C2 只有 0.669 req/s、P95 3.74 秒，代码追踪发现轮询加载完整结果和全部 Checkpoint；新增轻量状态投影。优化后数字尚待复测，体现了不虚构提升。

### 故事三：上下文压缩反而膨胀

首次实现从 1500 字符膨胀到 2397；将结构开销纳入预算、规范化硬约束并保留最近轮次后降到 990。主动保留失败结果比只展示最终数字更可信。

## 9. 面试时必须守住的边界

- 不说“完全自主 Agent”，而说固定工作流 + 条件路由 + 节点内按需 Tool/RAG；
- 不把 69.7 幂等 req/s 说成 Agent QPS；
- 不把 2～3 个样本 P95 说成 SLA；
- 不把单节点 Quorum 配置说成 RabbitMQ 集群 HA；
- 不把 Helm/Runbook 说成已完成生产 K8s 故障演练；
- 不把字符压缩 34% 说成 Token/成本降低 34%；
- 不写未经正式报告验证的零丢失、99.99%、RPO/RTO 和 RAG 提升比例；
- 对尚未复测的轻量状态接口只说“已优化、待 A/B 验证”。

## 10. 后续可以量化的闭环

1. 复测 `/status` 并填写 QPS/P95 提升；
2. 从服务器本地 Prometheus 采集 queue delay、节点和模型耗时；
3. A/B 测试 Worker concurrency 1/2 与双副本；
4. 固定索引做 RAG 三路消融和质量评测；
5. 做 30～60 分钟稳态与断线重连；
6. 受控故障演练后再填写恢复成功率与 RTO。
