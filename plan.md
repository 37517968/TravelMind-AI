# 行程智控：高并发、高可用 Agent 旅行决策平台改造计划

> 文档状态：规划稿，不代表当前代码已经实现  
> 基线日期：2026-09-18  
> 适用仓库：`travelmind-ai`  
> 目标：在保留现有 Spring AI、RAG、Agent Skills、Tool Calling、MCP、社区知识闭环能力的基础上，将项目升级为具备异步任务、可恢复工作流、分层记忆、上下文压缩、混合检索、服务治理、可观测和多副本部署能力的 Agent 平台。

---

## 1. 改造原则

### 1.1 总体原则

1. **先模块化单体，后按压力拆服务**：第一阶段在一个 Spring Boot 应用内完成清晰模块边界、异步任务和外部化状态；只有出现独立扩容、故障隔离或团队协作需求时再拆微服务。
2. **大模型负责概率性决策，程序负责确定性控制**：需求理解、偏好判断可以交给模型；状态流转、权限、预算、重试、幂等、时间冲突和数据一致性必须由代码控制。
3. **长任务不占用 HTTP 请求线程**：旅行规划通过 RabbitMQ 异步执行，HTTP 接口只创建任务并返回 `taskId`，执行进度通过 SSE 推送。
4. **状态全部外置**：禁止将会话、工作流检查点、检索索引和幂等状态只放在本地文件或单个 JVM 内存中，确保应用能够多副本运行。
5. **不同记忆使用不同存储**：短期上下文、完整聊天历史、用户画像、长期语义记忆、工作流状态分别建模，避免用 Redis 或向量库解决所有问题。
6. **可靠消息优先于分布式长事务**：默认采用本地事务、Transactional Outbox、RabbitMQ 和幂等消费实现最终一致性；不使用 Seata 包裹大模型或第三方 API 调用。
7. **先测量再写指标**：简历中的 QPS、P95、召回率、Token 降幅、成功率必须来自压测或离线评测，不能使用估算数字。

### 1.2 当前基线与主要问题

当前项目已经具备：

- Spring Boot 3.4.4、Java 21、Spring AI 1.0.0；
- DashScope / Ollama 模型接入；
- Agent Skills、Tool Calling、MCP Client；
- MySQL、Redis、MyBatis-Plus；
- PGVector 接入代码、BM25 + 向量混合检索雏形；
- 社区方案、评论、点赞、收藏和知识同步；
- SSE / Flux 流式输出；
- Docker Compose 单机部署。

当前需要解决的问题：

- `FileBasedChatMemory` 依赖本机文件，不支持多实例、并发写和统一过期策略；
- Agent 调用以同步链路为主，请求耗时受模型和第三方工具影响；
- 部分 BM25 文档、Token 和统计数据保存在 JVM 内存，重启和多实例时不一致；
- 定时知识同步在多副本环境下可能重复执行；
- 工具调用缺少统一的超时、重试、熔断、缓存、审计和错误分类；
- 缺少持久化工作流状态、节点检查点、失败恢复和人工确认能力；
- 缺少明确的上下文预算与滚动摘要策略；
- Docker Compose 仅用于本地/演示环境，不能等同于高可用部署；
- 缺少统一指标、链路追踪、AI 成本与 RAG 质量评测。

---

## 2. 目标架构与模块边界

```text
Web / App
   |
Nginx / Spring Cloud Gateway
   |-- 用户与社区模块
   |-- Agent 接入模块 ---- Redis（幂等、任务热状态、SSE事件）
   |                          |
   |                       RabbitMQ
   |                          |
   |                    Agent Harness Worker
   |                          |
   |        +-----------------+------------------+
   |        |                 |                  |
   |      记忆中心          RAG 中心           Tool/MCP 中心
   |        |                 |                  |
   |  Redis/MySQL/PGVector  ES/PGVector       天气/地图/搜索
   |        |                 |
   |        +-------- MySQL 事实数据源 ---------+
   |
WebSocket 通知 / SSE 规划进度

治理面：Nacos + Sentinel + Redisson
观测面：Actuator + Micrometer + Prometheus + Grafana + OpenTelemetry
部署面：Docker Compose（本地）/ Kubernetes（生产形态）
```

### 2.1 初期代码模块建议

第一阶段不立即建立多个独立仓库，在当前工程中按包隔离：

```text
com.travelmind.aiagent
├── gatewayadapter       # 请求模型、鉴权上下文、幂等入口
├── task                 # Agent任务、任务状态、生产者/消费者
├── harness              # 工作流定义、节点、路由、检查点、恢复
├── memory               # 短期/摘要/长期记忆与上下文组装
├── rag                  # 查询处理、召回、融合、重排、引用
├── tool                 # Tool/MCP注册、执行、治理、审计
├── community            # 行程社区、评论、点赞、收藏
├── knowledge            # 内容清洗、切片、索引、同步
├── notification         # SSE/WebSocket事件
├── governance           # Sentinel、Redisson、配置和限流
├── observability        # 指标、追踪、审计
└── infrastructure       # MySQL、Redis、RabbitMQ、ES、PG适配器
```

拆分服务的触发条件：

- Agent Worker 需要独立于 API 横向扩容；
- 知识索引任务明显影响在线请求；
- Tool 服务需要独立限流、隔离或密钥管理；
- 社区业务与 Agent 业务发布节奏不同；
- 单体部署已经无法满足故障隔离或资源配额要求。

建议后续最多先拆为四个部署单元：`gateway-api`、`agent-worker`、`knowledge-worker`、`community-service`，不要为了简历拆成大量空壳微服务。

---

## 3. 主流技术选型结论

| 能力 | 主选方案 | 备选方案 | 选型理由 |
|---|---|---|---|
| Agent SDK | Spring AI | LangChain4j | 当前代码已经深度使用 Spring AI，继续使用可减少双框架抽象冲突 |
| Harness 工作流 | 自有 Harness 接口 + 显式状态图；优先评估 Spring AI Alibaba Graph | Temporal / Camunda | 状态图适合 Agent 节点编排；通过自有接口隔离具体引擎，避免被实验性 API 绑定 |
| 可靠任务队列 | RabbitMQ Quorum Queue | RocketMQ / Kafka | 任务型消息、路由、重试和死信成熟；现有简历栈一致；Quorum Queue 适合关键任务可靠复制 |
| 短期会话记忆 | Spring AI `MessageWindowChatMemory` + `RedisChatMemoryRepository` | JDBC ChatMemoryRepository | 官方抽象支持窗口记忆；Redis 适合热上下文、多实例和 TTL |
| 完整聊天历史 | MySQL 独立消息表 | PostgreSQL | 聊天历史是审计事实，不等同于模型 Chat Memory；关系库适合分页、权限和删除 |
| 会话滚动摘要 | MySQL 持久化 + Redis 热缓存 | 仅 MySQL | 摘要需要版本、可恢复和审计，不能只存缓存 |
| 用户稳定画像 | MySQL 结构化字段 | MongoDB | 当前项目已有 MySQL，偏好字段可 JSON 化，不必新增文档库 |
| 长期语义记忆 | PGVector | Elasticsearch dense_vector / Milvus | 项目已有 PGVector；当前数据量下运维简单，并支持元数据过滤 |
| 社区全文检索 | Elasticsearch | OpenSearch | 中文全文、过滤、聚合、BM25 和运营搜索能力成熟 |
| RAG 混合检索 | Elasticsearch BM25 + PGVector + 应用层 RRF | Elasticsearch 单库混合检索 | 保留已有 PGVector 投资，同时引入 ES；RRF 不要求直接比较两套异构分数 |
| 热数据缓存 | Redis | Caffeine + Redis 二级缓存 | Redis 支持多实例；极热点只读数据可再加 Caffeine，但第一阶段不必引入 |
| 分布式锁 | Redisson | 数据库条件更新 | Redisson 适合索引任务和缓存重建；业务计数优先原子 SQL，避免滥用锁 |
| 进度事件 | Redis Streams + SSE | RabbitMQ + SSE | Streams 支持事件追加和短期回放；最终任务状态仍以 MySQL 为准 |
| 实时通知 | WebSocket | SSE | 双向通知用 WebSocket；单向 Agent 流式输出优先 SSE |
| 服务注册配置 | Nacos | Kubernetes Service + ConfigMap | Spring Cloud Alibaba 体系一致；若完全使用 K8s，可减少重复注册发现体系 |
| 流量治理 | Sentinel | Resilience4j | Sentinel 适合网关、热点参数、模型/工具维度限流；Resilience4j 更轻量但控制台能力弱 |
| 分布式事务 | 默认不引入 Seata | Seata AT/TCC | Agent 是长事务，主流程应采用 Saga/状态机 + 消息最终一致性；仅短时跨库强一致再评估 Seata |
| 数据库迁移 | Flyway | Liquibase | SQL 版本化简单、适合当前项目；生产禁止应用自动随意建表 |
| 可观测 | Actuator + Micrometer + Prometheus + Grafana + OpenTelemetry | SkyWalking | 与 Spring Boot / Spring AI 观测体系衔接自然 |
| 部署 | Docker Compose 本地，Kubernetes 生产形态 | 单机 Docker | 多副本、健康检查、滚动升级和 HPA 需要编排平台 |

### 3.1 版本治理要求

- 使用 BOM 管理 Spring Boot、Spring AI、Spring Cloud Alibaba 依赖，不手工混搭不兼容版本。
- 当前 Spring AI 1.0.0 与 Spring AI Alibaba 1.0.0.2 先完成架构重构；升级到 Spring AI 2.x 必须建立独立迁移分支并验证 Tool Calling、Advisor 顺序、ChatMemory 和 MCP 兼容性。
- 删除无实际使用的 AI 框架依赖，避免 Spring AI 与 LangChain4j 同时承担核心编排。
- 所有中间件版本写入 `versions.md` 或 BOM，并记录升级原因、兼容性和回滚方式。
- 本地 Compose 镜像禁止长期使用无版本的 `latest` 标签。

---

## 4. 模块一：领域模型与数据库基线改造

### 4.1 目标

建立任务、会话、消息、摘要、记忆、检查点和可靠事件的数据模型，为异步执行、多实例和故障恢复提供事实源。

### 4.2 新增核心表

#### `agent_task`

- `id`：雪花 ID/数据库主键；
- `request_id`：客户端幂等 ID，唯一索引；
- `user_id`、`conversation_id`；
- `task_type`：PLAN / MODIFY / QA；
- `status`：CREATED / QUEUED / RUNNING / WAITING_USER / SUCCEEDED / FAILED / CANCELLED；
- `workflow_version`、`current_node`；
- `request_json`、`result_json`；
- `error_code`、`error_message`；
- `version`：乐观锁版本；
- `created_at`、`started_at`、`finished_at`、`updated_at`。

#### `agent_workflow_checkpoint`

- `task_id`、`node_id`、`attempt` 组成唯一键；
- `node_status`；
- `input_snapshot`、`output_snapshot`；
- `state_snapshot`；
- `started_at`、`finished_at`；
- `error_type`、`retryable`；
- 大字段超过阈值时存对象存储，只在表中保存 URI 和摘要。

#### `chat_message`

- 保存完整聊天历史，不受 Chat Memory 窗口淘汰影响；
- 字段包含 `conversation_id`、`message_id`、`role`、`content`、`token_count`、`sequence_no`、`metadata_json`、`created_at`；
- `(conversation_id, sequence_no)` 唯一；
- 支持逻辑删除、用户数据导出和按会话清理。

#### `conversation_summary`

- `conversation_id`、`summary_version`；
- `covered_from_sequence`、`covered_to_sequence`；
- `summary_text`、`structured_state_json`；
- `source_hash`，防止并发重复摘要；
- `model_name`、`prompt_version`、`token_count`。

#### `user_memory`

- `memory_id`、`user_id`、`memory_type`；
- `content`、`structured_value_json`；
- `importance`、`confidence`、`source_message_id`；
- `valid_from`、`expires_at`、`last_accessed_at`；
- `status`：CANDIDATE / CONFIRMED / REJECTED / EXPIRED。

#### `outbox_event`

- `event_id`、`aggregate_type`、`aggregate_id`；
- `event_type`、`payload_json`；
- `status`、`retry_count`、`next_retry_at`；
- `created_at`、`published_at`；
- 业务写入和 Outbox 事件必须处于同一本地事务。

### 4.3 数据库要求

- 使用 Flyway 管理所有表结构和索引变更；
- 为任务状态、会话消息分页、待发布 Outbox、待重试节点建立组合索引；
- 所有状态更新使用乐观锁或带旧状态条件的原子 SQL，例如 `WHERE status = 'QUEUED'`；
- 禁止依赖“先查再改”保证并发正确性；
- JSON 字段只保存扩展结构，常用过滤字段单独建列；
- 定义数据保留策略：原始工具结果、聊天历史、检查点、审计日志分别配置保留期。

### 4.4 验收标准

- Flyway 能从空库完整创建结构；
- 同一 `request_id` 并发提交只能创建一个任务；
- 同一任务不能被两个 Worker 同时从 QUEUED 更新为 RUNNING；
- 能从数据库还原任意任务的当前节点、已完成节点和失败原因；
- Chat Memory 淘汰旧消息后，完整聊天历史仍可查询。

---

## 5. 模块二：异步任务与削峰改造

### 5.1 目标

将旅行规划从同步长请求改造成“提交任务—后台执行—流式观察—结果查询”的异步模式。

### 5.2 API 设计

- `POST /api/agent/tasks`：提交任务，要求携带 `Idempotency-Key`；
- `GET /api/agent/tasks/{taskId}`：查询最终状态和结果；
- `GET /api/agent/tasks/{taskId}/events`：建立 SSE 连接；
- `POST /api/agent/tasks/{taskId}/cancel`：发出取消请求；
- `POST /api/agent/tasks/{taskId}/resume`：用户补充信息后恢复；
- 保留简单问答同步接口，但必须设置严格超时和能力边界。

### 5.3 RabbitMQ 拓扑

```text
exchange: agent.command (topic)
  routing key: plan.create
  routing key: plan.modify
  routing key: task.resume

queue: agent.plan.q                 # Quorum Queue
queue: agent.plan.retry.10s.q
queue: agent.plan.retry.60s.q
queue: agent.plan.dlq

exchange: knowledge.event (topic)
queue: knowledge.index.q            # Quorum Queue
queue: knowledge.index.dlq
```

要求：

- 开启 Publisher Confirm，发布失败时由 Outbox Publisher 重试；
- 消费端手动 ACK，只有任务检查点和状态提交成功后才确认；
- 消费必须幂等，使用 `eventId` / `taskId + nodeId + attempt` 去重；
- 可重试异常进入延迟重试队列，不可重试异常直接进入 DLQ；
- DLQ 必须提供管理接口或运维脚本进行查看、重放和归档；
- 设置队列长度、消息 TTL、单消息大小和消费者 prefetch 上限；
- 不允许在 RabbitMQ 消息中放完整大模型上下文，只传 ID 和必要路由字段。

### 5.4 背压与资源隔离

- Agent Worker 消费并发数不得超过模型账户配额；
- 使用信号量/隔离线程池控制每个模型、每个 Tool 的并发；
- 按用户套餐或优先级设置逻辑队列，不让单一用户占满资源；
- 消息积压达到阈值时，入口返回排队状态或触发降级，不继续无限接收；
- 记录排队时间、执行时间、模型时间、工具时间，区分慢在哪里。

### 5.5 验收标准

- 提交接口不等待模型结果即可返回 `taskId`；
- Worker 异常退出后，未 ACK 的消息可由其他 Worker 继续处理；
- 重复消息不会重复创建行程或重复写长期记忆；
- 可查询任务排队、执行、等待用户、完成和失败状态；
- DLQ 消息能够人工修复后安全重放。

---

## 6. 模块三：Agent Harness 工作流编排

### 6.1 Harness 定义

本项目中的 Harness 是包裹大模型的 Agent 运行时，不等同于 Harness.io。它负责：

- 工作流定义和版本管理；
- Spring AI Alibaba StateGraph 固定节点与条件边；
- 状态持久化和检查点；
- 超时、重试、降级和补偿；
- Token、调用次数和成本预算；
- 用户确认和暂停/恢复；
- 节点级事件与可观测性；
- 最终结果校验。

### 6.2 工作流状态

```text
CREATED
  -> CONSTRAINT_EXTRACTION（白名单 TravelConstraintSpec）
  -> CONSTRAINT_VALIDATION
       \-- 缺少目的地/预算 -> WAITING_USER -> RESUME
  -> CONTEXT_BUILDING（Hybrid RAG）
  -> CANDIDATE_RETRIEVAL（ToolGateway / Local Tool / MCP）
  -> CONSTRAINT_SOLVING（Z3，失败时 JVM Solver 降级）
       |-- UNSAT/UNKNOWN -> UNSAT_RELAXATION -> WAITING_USER -> RESUME
       \-- SAT -> ITINERARY_GENERATION（流式）
  -> DETERMINISTIC_VALIDATION
       \-- INVALID -> UNSAT_RELAXATION
  -> FRESHNESS_RECHECK
       \-- STALE -> UNSAT_RELAXATION
  -> PERSISTING
  -> SUCCEEDED / FAILED / CANCELLED
```

StateGraph 负责业务路由，Harness 负责状态、预算、超时、重试、恢复、校验和持久化。LLM 只做结构化约束抽取和最终行程表述，不决定下一跳。天气、POI、路线和 MCP 工具由候选采集节点按约束需要调用；每个固定图节点使用带补充版本的 `nodeId` 保存检查点。

执行预算只在任务首次创建时建立。`maxModelCalls`、`maxTokens`、`maxNodeExecutions`、`maxAgentSteps` 以及任务身份字段不能通过 `resume` 修改；旅行预算、日期、天数、节奏和偏好等业务约束允许补充或调整。恢复任务只增加补充版本，不清空检查点，也不重置已消耗预算。

### 6.3 节点统一协议

每个节点实现统一接口并声明：

- `nodeId`、`nodeVersion`；
- 输入/输出 JSON Schema；
- 超时时间；
- 最大重试次数；
- 是否幂等；
- 是否可并行；
- 是否允许降级；
- 失败分类；
- 补偿节点；
- Token 和成本预算。

节点执行结果不得只返回字符串，应返回：

```json
{
  "status": "SUCCESS",
  "data": {},
  "evidence": [],
  "warnings": [],
  "metrics": {},
  "nextHints": []
}
```

### 6.4 工作流引擎选型要求

已采用：项目保留 `WorkflowEngine`、`WorkflowState`、`NodeExecutor`、`CheckpointStore` 可靠性接口，业务路由使用 Spring AI Alibaba `StateGraph / OverAllState / CompiledGraph`。

选择 Graph 的前提：

- 支持条件边、并行节点、循环上限；
- 支持自定义持久化检查点；
- 支持中断、恢复和人工输入；
- 能与现有 Spring AI ChatClient、Tool Calling、Advisor 兼容；
- API 版本稳定，升级成本可接受。

当前 Graph 的内存 checkpoint 不作为事实源；每个 Graph 节点仍由项目 Harness 写入 MySQL checkpoint。只有出现跨天工作流、复杂定时器、大量补偿和跨团队编排时，再评估 Temporal/Camunda。

### 6.5 恢复与重试

- 每个有外部副作用的节点执行前后都保存检查点；
- 模型超时、网络抖动、第三方 5xx 可重试；参数错误、权限错误、预算耗尽不可重试；
- 重试采用指数退避 + 抖动；
- 修复循环设置最大次数，例如最多 2 次，防止 Agent 无限反思；
- 恢复时从最近成功检查点继续，已经成功且幂等的工具节点不重复调用；
- 取消任务只设置取消标志，节点在安全点检查并退出；
- 工作流定义带版本，运行中的旧任务继续使用创建时版本。

### 6.6 验收标准

- 能可视化或通过接口查看每个节点的状态和耗时；
- Worker 重启后可从检查点继续；
- 缺少目的地/日期等关键参数时可暂停并等待用户；
- 并行节点全部完成后才能进入依赖节点；
- 重试不会重复产生社区内容、通知或长期记忆；
- 超过模型调用/Token/修复次数预算后能够明确终止并解释原因。

---

## 7. 模块四：分层记忆系统

### 7.1 记忆分层

#### A. 工作记忆（Working Memory）

用途：当前任务需要的最近消息、当前状态、临时工具结果。  
存储：Redis。  
生命周期：会话活跃期，默认 24 小时，可配置。  
实现：`MessageWindowChatMemory + RedisChatMemoryRepository`，窗口大小按 Token 而不是只按消息数二次限制。

#### B. 完整聊天历史（Chat History）

用途：用户查看、审计、重放、摘要重建。  
存储：MySQL `chat_message`。  
生命周期：按隐私和业务策略保留。  
要求：Chat Memory 淘汰不允许删除 Chat History。

#### C. 会话摘要（Conversation Summary）

用途：替代较早的原始对话进入模型上下文。  
存储：MySQL 持久化，Redis 缓存最新版本。  
内容：目标、已确认约束、偏好、否决项、已完成步骤、未决问题、关键引用。

#### D. 用户画像（Profile Memory）

用途：稳定偏好、无障碍要求、预算倾向、出行习惯。  
存储：MySQL 结构化字段。  
要求：区分用户明确确认的信息和模型推断信息；推断信息必须带置信度，不得自动覆盖用户确认值。

#### E. 情景记忆（Episodic Memory）

用途：曾经完成的行程、修改原因、真实反馈。  
存储：MySQL 事实记录，摘要向量可进入 PGVector。  
召回：按用户、时间、目的地和相似度联合过滤。

#### F. 长期语义记忆（Semantic Memory）

用途：从历史中召回与当前任务相关的稳定偏好和经验。  
存储：PGVector。  
要求：只写高价值记忆，不把每一句聊天都向量化。

### 7.2 Redis Key 设计

```text
chat:window:{userId}:{conversationId}          # 最近消息，TTL
chat:summary:{conversationId}                  # 最新摘要缓存，TTL
agent:task:{taskId}                            # 任务热状态，TTL
agent:lock:conversation:{conversationId}       # 会话串行锁
agent:events:{taskId}                          # Redis Stream，有限长度 + TTL
idem:agent-request:{userId}:{requestId}        # 幂等键
rate:model:{modelName}:{window}                # 模型限流计数
cache:tool:{toolName}:{requestHash}             # 工具结果缓存
```

要求：

- Key 必须包含业务前缀和版本；
- 所有非永久 Key 必须设置 TTL；
- 大 value 禁止进入 Redis；
- 使用 JSON/明确序列化协议，禁止依赖无法跨版本读取的本地 Kryo 文件；
- Redis 失效时可从 MySQL 重建关键状态；
- 同一会话写入使用版本号/CAS 或 Redisson 锁，防止并发覆盖摘要。

### 7.3 长期记忆写入规则

允许写入：

- 用户明确表达并可复用的稳定偏好；
- 多次重复出现且置信度达到阈值的习惯；
- 用户确认的重要约束；
- 对已执行行程的真实反馈；
- 用户主动要求系统记住的信息。

禁止写入：

- 天气、票价、营业时间等短时数据；
- 未确认的模型猜测；
- 工具原始响应；
- 密码、令牌、支付信息和不必要的敏感信息；
- 与未来任务无复用价值的寒暄。

### 7.4 记忆召回排序

第一版采用可解释打分：

```text
memoryScore =
  semanticSimilarity
  * importanceWeight
  * confidenceWeight
  * timeDecay
```

同时使用 `userId`、`memoryType`、`status`、`expiresAt` 做硬过滤。召回结果必须携带来源消息和时间，防止过期偏好污染决策。

### 7.5 隐私与生命周期

- 提供清除单个会话、清除长期记忆、导出个人数据能力；
- 用户删除会话时同步删除 Redis、MySQL 和 PGVector 中的派生记忆；
- 日志默认不记录 Prompt、完整回复、Tool 参数和 Tool 结果；
- 对手机号、身份证、精确住址等信息做脱敏；
- 所有长期记忆写入和读取记录审计事件。

### 7.6 验收标准

- 任意应用副本都能读取同一会话窗口；
- Redis 清空后可从 MySQL 恢复摘要和必要会话状态；
- 最近消息、摘要、画像和长期语义记忆可分别开关；
- 用户否认某条推断偏好后，该偏好不会再次参与召回；
- 删除用户记忆后，向量记录和缓存同步失效。

---

## 8. 模块五：上下文构建与压缩

### 8.1 目标

在模型上下文窗口内优先保留安全规则、当前任务事实和高相关证据，控制 Token 成本，并避免简单截断造成约束丢失。

### 8.2 Context Envelope

所有模型节点统一接收 `ContextEnvelope`：

```text
systemPolicies          系统规则与安全约束
skillInstructions       当前节点需要的 Skill，不加载全部 Skill
workflowState           当前结构化任务状态
userProfile             与当前任务相关的用户画像
conversationSummary     已压缩的历史摘要
recentMessages          最近原始消息
retrievedEvidence       RAG 证据及来源
toolEvidence            最新工具结果及时间戳
budget                  Token/调用次数/成本预算
```

### 8.3 Token 预算策略

先为输出保留模型窗口的 20%～30%，其余作为输入预算。输入预算建议初始分配：

| 内容 | 输入预算占比 |
|---|---:|
| System Policy + 当前 Skill | 15% |
| 工作流结构化状态 | 15% |
| 最近原始对话 | 20% |
| 会话摘要 + 长期记忆 | 10% |
| RAG 证据 | 25% |
| Tool 结果 | 15% |

具体比例必须按节点动态调整。例如路线校验节点提高 Tool 证据预算，闲聊节点不加载完整 RAG。

### 8.4 四级压缩策略

#### L0：确定性清洗

- 删除重复消息、重复 Chunk、无用 HTML 和日志；
- Tool 原始 JSON 映射为最小字段 DTO；
- 天气、价格、营业时间只保留最新且未过期值；
- 大文档保留引用 ID，不直接重复全文；
- 对 RAG 结果做相邻 Chunk 合并和内容去重。

#### L1：窗口裁剪

- 保留最近 6～10 轮消息，实际以 Token 上限为准；
- System Message 和当前用户请求不可被裁掉；
- Tool 调用过程默认不作为长期 Chat Memory，但关键结论进入结构化工作流状态；
- 不按字符粗暴截断 JSON，必须按字段缩减。

#### L2：滚动摘要

当预计输入超过预算的 70%～75% 时触发：

- 保留最近若干轮原文；
- 将更早消息压缩成结构化摘要；
- 摘要必须保留已确认事实、否决项、未决问题和来源范围；
- 使用 `source_hash + summary_version` 防止并发重复摘要；
- 摘要生成失败时回退到旧摘要，不覆盖有效版本。

#### L3：相关性召回

- 长期记忆只召回当前问题相关 TopK；
- RAG 采用 metadata 过滤后再做相似度召回；
- 超预算时优先删除低相关、过期、低置信度内容；
- 对关键硬约束设置不可驱逐标记，例如日期、预算、人数和无障碍要求。

### 8.5 摘要质量要求

摘要输出必须符合 JSON Schema，并包含：

- `goal`；
- `confirmedConstraints`；
- `preferences`；
- `rejectedOptions`；
- `decisions`；
- `completedSteps`；
- `pendingQuestions`；
- `evidenceRefs`；
- `coveredMessageRange`。

摘要后执行规则校验：预算、日期、人数等关键字段必须与原始结构化状态一致。涉及事实冲突时，以用户最后明确确认的消息为准。

### 8.6 验收标准

- 构造上下文前能准确估算 Token，并为输出保留预算；
- 100 轮会话仍能保留最初确认的预算和旅行偏好；
- 上下文中不存在大段重复 Tool JSON 或重复 RAG Chunk；
- 压缩前后关键约束一致；
- 能统计每类上下文占用的 Token 和被淘汰原因；
- 与“全部历史直接拼接”基线对比 Token、质量和延迟。

---

## 9. 模块六：RAG 与知识检索改造

### 9.1 数据职责

- MySQL：社区内容与知识实体的事实源；
- Elasticsearch：全文检索、BM25、中文分词、过滤和聚合；
- PGVector：语义向量、长期记忆和知识 Chunk；
- Redis：查询结果短缓存，不作为知识事实源。

### 9.2 索引流水线

```text
社区内容提交
 -> MySQL 本地事务
 -> Outbox Event
 -> RabbitMQ
 -> 质量检查
 -> PII/敏感内容处理
 -> 文档结构解析
 -> 语义切片
 -> 元数据提取
 -> Embedding
 -> 写入 PGVector
 -> 写入 Elasticsearch
 -> 更新索引版本
```

### 9.3 Chunk 与元数据

每个 Chunk 至少包含：

- `documentId`、`chunkId`、`contentVersion`；
- `sourceType`、`sourceId`、`sourceUrl`；
- `city`、`district`、`poiIds`；
- `travelType`、`season`、`suitableCrowd`；
- `publishedAt`、`validFrom`、`validTo`；
- `qualityScore`、`likeCount`；
- `contentHash`、`embeddingModelVersion`；
- `status`、`isDeleted`。

切片优先按标题、段落、日期行程和列表语义边界；固定 Token 仅作为兜底。父文档和子 Chunk 均保留引用关系，召回子块后可扩展相邻块或父摘要。

### 9.4 检索流水线

1. 意图和实体提取；
2. 查询重写，但保留原查询供审计；
3. 目的地、日期、旅行类型等元数据过滤；
4. Elasticsearch BM25 召回；
5. PGVector 语义召回；
6. 使用 RRF 合并异构排名；
7. 质量、时效、个性化特征轻量调整；
8. 可选 Cross-Encoder / Reranker 对 TopN 重排；
9. 内容去重、证据压缩和引用生成；
10. 无可靠证据时明确降级，不让模型编造事实。

RRF 第一版优先于直接加权分数，因为 BM25 和向量相似度量纲不同，RRF 不需要先做复杂归一化。有标注数据后再评估线性融合和 Learning-to-Rank。

### 9.5 一致性策略

- MySQL 为唯一事实源；
- ES/PGVector 写入失败不回滚社区业务，而是由 Outbox 重试；
- 消费端以 `sourceId + contentVersion` 幂等；
- 删除采用 Tombstone 事件同步到两个索引；
- 提供全量重建索引能力和蓝绿索引别名切换；
- 定期执行 MySQL 与索引的数量、版本、Hash 对账。

### 9.6 评测要求

建立至少 100～300 条旅行领域评测集，包含：

- 明确目的地查询；
- 模糊意图查询；
- 多约束组合查询；
- 时效性查询；
- 中文别名和口语表达；
- 无答案问题；
- 对抗性/错误前提问题。

指标：Recall@K、Precision@K、MRR、NDCG、引用正确率、回答忠实度、无答案识别率、检索 P95。

### 9.7 验收标准

- JVM 重启不影响 BM25 索引可用性；
- 同一文档重复投递不会产生重复 Chunk；
- 可追溯回答引用到具体源文档和版本；
- 支持按城市、区域、旅行类型、有效期过滤；
- 混合检索在离线评测集上优于纯 BM25 和纯向量基线；
- 能全量重建索引且不长时间中断在线查询。

---

## 10. 模块七：Tool Calling 与 MCP 治理

### 10.1 工具分级

- **只读低风险**：天气、POI、路线、搜索；
- **有成本**：付费搜索、模型、地图高级接口；
- **有副作用**：保存方案、发送通知、未来的预订操作；
- **高风险**：文件系统、Shell、外部写操作。

生产环境禁止把通用 Shell/FileSystem 工具默认暴露给模型。工具应按工作流节点动态注册，只向模型提供当前节点允许使用的最小工具集合。

### 10.2 统一 Tool Gateway

所有工具执行经过统一拦截器：

```text
参数校验
 -> 权限/风险检查
 -> 幂等检查
 -> 缓存查询
 -> Sentinel限流/熔断
 -> 超时与重试
 -> 实际调用
 -> 结果Schema校验
 -> 脱敏与裁剪
 -> 缓存/审计/指标
```

### 10.3 工具返回协议

禁止向模型返回不可控长文本，统一返回：

- `success`；
- `data`；
- `source`；
- `observedAt`；
- `expiresAt`；
- `errorCode`；
- `retryable`；
- `degraded`；
- `warnings`。

天气、价格、营业时间必须带时间戳和过期时间。路线工具返回距离、时长、交通方式和坐标，不返回完整第三方原始响应。

### 10.4 容错规则

- 连接超时和读取超时分开配置；
- 只对幂等读请求执行有限重试；
- 使用指数退避，禁止立即连续重试放大故障；
- 地图/天气故障时优先读取短时缓存并标记 `degraded=true`；
- Tool 失败不能直接把异常堆栈交给模型；
- 连续失败触发 Sentinel 熔断；
- 同一请求内相同 Tool 参数使用请求级去重。

### 10.5 MCP 要求

- 生产优先使用受控的远程 MCP 服务，不使用依赖本机 Windows 命令的 STDIO 配置；
- MCP Server 必须进行身份认证、超时和允许列表控制；
- MCP Tool Schema 纳入版本管理；
- 对 MCP 返回结果执行与本地 Tool 相同的校验、脱敏和观测；
- MCP 不可用时不影响基础旅行规划能力。

### 10.6 验收标准

- 每个 Tool 都有超时、错误码、Schema 和单元测试；
- 高风险工具不会被未授权工作流加载；
- 同一参数的天气请求在 TTL 内命中缓存；
- 第三方接口故障不会耗尽 Worker 线程；
- 能统计各工具 P50/P95、成功率、缓存命中率和调用成本。

---

## 11. 模块八：社区知识闭环与并发控制

### 11.1 社区并发改造

- 点赞/收藏使用唯一键保证一人一次；
- 计数优先使用数据库原子更新，Redis 用于热点读缓存；
- 缓存更新采用 Cache-Aside，写库成功后删除缓存；
- 热点 Key 重建使用 Redisson 锁和逻辑过期，防止缓存击穿；
- 空结果短 TTL 缓存防止缓存穿透；
- 不使用一个全局分布式锁串行所有社区请求。

### 11.2 知识入库质量门槛

社区内容进入 RAG 前检查：

- 内容完整度；
- 点赞、收藏、评论等质量信号；
- 作者可信度；
- 重复度；
- 时效性；
- 敏感信息和广告；
- 用户举报状态。

第一版使用可解释规则评分；积累足够标注和行为数据后再引入学习排序，不伪造“AI 自动判断高质量”的指标。

### 11.3 反馈闭环

- 记录用户采纳、修改、删除的行程项；
- 区分“检索结果被使用”和“用户真正采纳”；
- 将负反馈转为评测样本，不直接让模型自动修改知识库；
- 高质量内容经审核/阈值后发布索引事件；
- 低质量或过期内容发布撤销事件。

### 11.4 验收标准

- 并发点赞不会出现负数、重复记录或计数严重漂移；
- 社区事务成功后即使索引服务宕机，恢复后仍能完成索引；
- 内容删除后 ES 和 PGVector 最终都不可召回；
- 能解释某条社区内容为何被纳入或排除知识库。

---

## 12. 模块九：幂等、缓存与分布式并发

### 12.1 幂等层级

- HTTP：`Idempotency-Key + userId + operation`；
- MQ：`eventId` 消费记录；
- 工作流节点：`taskId + nodeId + attempt`；
- Tool：业务幂等键或请求 Hash；
- 索引：`sourceId + contentVersion + chunkId`；
- 通知：`userId + notificationType + bizId`。

### 12.2 锁使用原则

优先顺序：

1. 数据库唯一约束；
2. 带条件的原子 SQL；
3. Redis Lua 原子操作；
4. 乐观锁；
5. 最后才使用 Redisson 分布式锁。

必须使用锁的场景：

- 同一会话摘要合并；
- 全量索引切换；
- 热点缓存重建；
- 多实例下只能单次执行的维护任务。

每把锁必须设置租约/看门狗策略、明确粒度和失败行为，禁止无超时等待。

### 12.3 缓存要求

- Cache Key 包含版本；
- TTL 加随机抖动，防止同时过期；
- 天气、POI、路线、热门方案分别配置 TTL；
- 不缓存用户敏感完整 Prompt；
- 缓存命中结果必须保留数据时间戳；
- 关键业务不能只依赖缓存完成持久化。

### 12.4 验收标准

- 重复 HTTP、重复 MQ 和 Worker 重启不会产生重复副作用；
- 热点缓存失效时不会同时打满数据库/第三方 API；
- 锁持有者崩溃后锁能够释放；
- Redis 故障时系统可以降级查询 MySQL，而不是直接数据丢失。

---

## 13. 模块十：服务治理与一致性边界

### 13.1 Nacos

仅在拆分为多个部署单元后引入注册发现；配置中心可提前用于：

- 模型路由和开关；
- Prompt/Skill 版本；
- Tool 超时和限流；
- RAG TopK、阈值和融合参数；
- 功能灰度开关。

密钥不得明文存入 Nacos，使用环境 Secret 或专用密钥管理服务。

### 13.2 Sentinel

保护资源：

- `agent-task-create`：入口 QPS；
- `agent-user-concurrency`：单用户并发；
- `llm-{provider}-{model}`：模型并发和慢调用；
- `tool-weather`、`tool-amap`、`tool-search`：第三方接口；
- `rag-search`：检索服务。

降级链路：

```text
主模型 -> 备用模型 -> 简化规划模式 -> 模板化结果
实时工具 -> 未过期缓存 -> 明确标记信息缺失
混合检索 -> 单路向量/关键词检索 -> 无RAG安全回答
```

### 13.3 Seata 使用边界

默认不在以下场景使用 Seata：

- 大模型推理；
- 第三方天气/地图调用；
- RabbitMQ 消息处理；
- ES/PGVector 索引同步；
- 可能等待用户输入的工作流。

只有未来出现“两个内部关系数据库、持续时间短、必须同步强一致”的场景才进行 Seata POC。其他场景使用本地事务 + Outbox + 幂等消费 + 对账补偿。

### 13.4 验收标准

- 动态调整限流规则无需重启；
- 主模型不可用时能按配置降级；
- 服务注册/配置中心短暂不可用不导致现有实例立即停止服务；
- 能通过对账任务发现 MySQL、ES、PGVector 版本不一致并自动补偿。

---

## 14. 模块十一：SSE、WebSocket 与通知

### 14.1 通道职责

- SSE：Agent 单向进度和 Token 流；
- WebSocket：社区互动、系统通知等双向长连接；
- RabbitMQ：服务之间的可靠事件；
- Redis Streams：短期任务进度事件和断线续传；
- MySQL：最终任务状态和通知记录。

### 14.2 SSE 事件协议

```text
event: task.accepted
event: node.started
event: node.progress
event: tool.started
event: tool.finished
event: answer.delta
event: task.waiting_user
event: task.completed
event: task.failed
event: heartbeat
```

每个事件包含 `eventId`、`taskId`、`sequence`、`timestamp`、`type`、`payload`。客户端通过 `Last-Event-ID` 请求补发，过期事件无法补发时回退到任务查询接口。

### 14.3 多实例要求

- SSE 连接可以落在任意 API 副本；
- Worker 不直接依赖某个 API 实例推送；
- Worker 将进度写入 Redis Stream，API 副本订阅并转发；
- Stream 设置最大长度和 TTL，避免无限增长；
- Redis Stream 不是最终事实源，任务完成状态必须写 MySQL。

### 14.4 验收标准

- 客户端短暂断线后能够补发未消费事件；
- API 实例重启不会导致任务本身失败；
- 慢客户端不会无限占用内存；
- 心跳、完成、失败均能正确关闭连接。

---

## 15. 模块十二：安全体系

### 15.1 身份与权限

- JWT 使用短期 Access Token + 可撤销 Refresh Token；
- 管理接口、索引重建、DLQ 重放采用 RBAC；
- 任务、会话、行程查询必须校验资源所有者；
- MCP 和内部服务使用服务身份认证，不信任内网即安全。

### 15.2 Prompt Injection 与工具安全

- System Policy 与外部检索内容严格分区；
- 将网页、社区内容标记为不可信数据，不允许其覆盖系统指令；
- Tool 参数经过 Schema、权限和业务规则校验，不能直接执行模型生成的 Shell；
- 外部 URL 访问防 SSRF，限制协议、域名、重定向和响应大小；
- 下载文件校验 MIME、大小和存储路径；
- 有副作用工具必须设置确认策略和审计记录。

### 15.3 密钥与日志

- API Key、数据库密码通过环境 Secret/Kubernetes Secret 注入；
- 仓库只保留 `.env.example`；
- Prompt、Completion、Tool 参数默认不写生产日志；
- 必须记录内容时先脱敏并限制采样比例；
- 定期轮换第三方 API Key。

### 15.4 验收标准

- 越权访问他人任务和会话被拒绝；
- 检索文档中的恶意指令不能调用高风险工具；
- 仓库扫描无明文生产密钥；
- 用户删除请求能覆盖缓存、数据库和向量记忆。

---

## 16. 模块十三：可观测性与 AI 成本治理

### 16.1 技术选型

- Spring Boot Actuator；
- Micrometer；
- Prometheus；
- Grafana；
- OpenTelemetry Trace；
- 可选 Loki/ELK 统一日志。

Spring AI 已提供 ChatClient、Advisor、ChatModel、EmbeddingModel、VectorStore 和 Tool Calling 的观测入口，优先接入官方 Observation，不重复制造不可关联的自定义日志。

### 16.2 核心指标

#### HTTP/系统

- QPS、P50/P95/P99；
- 错误率；
- JVM、GC、线程池、连接池；
- Redis、MySQL、RabbitMQ、ES/PG 连接状态。

#### Agent

- 排队时间、总执行时间；
- 节点耗时和重试次数；
- 成功、失败、取消、等待用户比例；
- 工作流恢复次数；
- 每任务 LLM 调用次数。

#### AI 成本

- 输入/输出 Token；
- 模型调用成本；
- 首 Token 延迟；
- 上下文各部分 Token 占比；
- 摘要节省 Token；
- 缓存命中减少的调用量。

#### Tool/RAG

- Tool 成功率、超时率、熔断次数；
- RAG 检索延迟、空召回率；
- 引用覆盖率；
- ES/PGVector 召回与 RRF 融合耗时；
- 索引积压和失败数。

### 16.3 Trace 要求

统一关联：`traceId`、`requestId`、`taskId`、`conversationId`、`workflowId`、`nodeId`、`toolCallId`、`messageId`。禁止将 `userId` 等高基数字段直接作为 Prometheus 标签，可放在受控 Trace/日志字段中。

### 16.4 告警

- RabbitMQ 积压持续增长；
- Agent 失败率或 P95 异常；
- 模型配额/Token 成本异常；
- Tool 熔断；
- Outbox 未发布事件积压；
- DLQ 出现消息；
- ES/PGVector 索引版本漂移；
- Redis 内存和淘汰异常。

### 16.5 验收标准

- 能从一次用户请求追踪到 MQ、工作流节点、模型、RAG 和 Tool；
- Grafana 能查看性能、可靠性、质量和成本四类看板；
- Prompt 和 Tool 内容默认不进入 Trace；
- 关键告警包含任务定位信息和处置说明。

---

## 17. 模块十四：部署与高可用

### 17.1 环境划分

- `local`：Docker Compose，单节点中间件，便于开发；
- `test`：多实例应用 + 独立中间件，执行集成测试和故障注入；
- `prod-like`：Kubernetes，多副本应用与高可用中间件形态。

### 17.2 应用部署

- Gateway / Agent API 至少 2 副本；
- Agent Worker 独立 Deployment，根据队列深度和资源指标扩缩容；
- Knowledge Worker 独立资源池，避免 Embedding 任务影响在线规划；
- 配置 readiness、liveness、startup probe；
- 使用优雅停机：停止取新消息、等待当前节点检查点完成、关闭连接；
- 设置 requests/limits，避免单个 Worker 占满节点；
- 使用滚动发布，数据库迁移遵循向后兼容的 Expand/Contract 模式。

### 17.3 中间件高可用形态

- RabbitMQ：3 节点，关键队列使用 Quorum Queue；
- Redis：优先托管服务或 Redis Cluster/Sentinel，开启与业务重要性匹配的持久化；
- MySQL：主从/高可用托管实例，定期备份与恢复演练；
- Elasticsearch：至少 3 个 master-eligible 节点的合理生产拓扑；
- PostgreSQL/PGVector：主备或托管高可用；
- 生产环境优先使用成熟托管服务，不为了展示 K8s 把所有状态组件都自行运维。

### 17.4 CI/CD

流水线：

```text
代码检查
 -> 单元测试
 -> 集成测试
 -> 依赖/密钥/镜像扫描
 -> 构建镜像
 -> 部署测试环境
 -> 冒烟测试
 -> 人工批准/自动策略
 -> 灰度发布
 -> 指标观察
 -> 全量或回滚
```

如果使用 Harness.io，只用于 CI/CD、环境治理和发布流程，不在简历中把它描述为 Agent 工作流引擎。

### 17.5 验收标准

- 任意一个 Agent API 副本退出不影响已有任务执行；
- 任意一个 Worker 退出后消息可重新分配；
- 滚动升级期间任务创建和查询可用；
- 数据库和 Redis 完成备份恢复演练；
- 生产配置不依赖本地 Windows 路径或 STDIO MCP 命令。

---

## 18. 模块十五：测试、压测与质量评估

### 18.1 测试金字塔

- 单元测试：节点路由、上下文预算、摘要合并、幂等、RRF；
- 合约测试：Tool/MCP Schema、RabbitMQ 消息 Schema；
- Testcontainers 集成测试：MySQL、Redis、RabbitMQ、PostgreSQL/PGVector、Elasticsearch；
- 端到端测试：提交任务、SSE、暂停、恢复、取消、失败补偿；
- AI 离线评测：RAG、事实性、约束遵循、行程可执行性；
- 故障注入：模型超时、MQ 重投、Redis 重启、工具 5xx、Worker 崩溃。

### 18.2 压测场景

使用 Gatling 或 JMeter，分开测试：

1. 任务提交接口吞吐，不包含大模型执行；
2. SSE 长连接数量与事件推送；
3. Worker 在模型配额下的稳定并发；
4. 社区查询、点赞和热门缓存；
5. RAG 混合检索；
6. MQ 突发积压和恢复速度；
7. 上下文长度从短到长的延迟和成本变化。

### 18.3 初始目标值

以下是改造验收目标，不是当前已达到指标，最终以测试报告为准：

- 任务提交 API：P95 小于 300 ms（不含外部鉴权异常）；
- 幂等正确率：100%；
- 关键 MQ 消息：无已确认消息静默丢失；
- Agent API 单实例故障：不丢任务；
- 工作流检查点恢复成功率：大于 99%；
- Tool 超时必须在配置时间内结束，不能无限等待；
- RAG 混合召回指标高于纯 BM25 和纯向量基线；
- 上下文压缩后硬约束保持率：100%；
- 所有简历性能数字都能在 `docs/benchmark/` 找到复现脚本和报告。

### 18.4 验收产物

- 测试用例清单；
- 压测脚本；
- 环境与数据规模说明；
- 原始结果和 Grafana 截图；
- 结论、瓶颈、优化前后对比；
- 未达标项和后续计划。

---

## 19. 分阶段实施路线

### Phase 0：基线与工程治理

- [x] 建立架构决策记录 ADR；
- [x] 使用 Flyway 接管表结构；
- [x] 清理依赖和配置，建立版本兼容矩阵；
- [x] 补齐核心单元测试和 Testcontainers（当前机器无 Docker daemon，容器测试已配置为自动跳过）；
- [x] 建立性能、RAG 和 Token 成本的测量口径与初始基线（真实 HTTP 性能与 Token 成本暂记为 `NOT_MEASURED`，禁止虚构指标）。

完成标志：现有功能有可重复测试，后续改造可以量化对比。

### Phase 1：状态外置与记忆改造

- [x] 用 Redis List 滑动窗口实现多副本共享 ChatMemory；
- [ ] 新增完整 Chat History、Summary、User Memory 表；
- [ ] 实现 Message Window、滚动摘要和 Context Envelope；
- [ ] 加入会话并发控制、TTL 和隐私删除；
- [x] 移除对本机记忆文件的生产依赖。

完成标志：应用可运行两个副本并共享同一会话。

### Phase 2：异步任务与 Harness

- [x] 新增 Agent Task 和 Checkpoint；
- [x] 接入 RabbitMQ、Outbox、幂等消费和 DLQ；
- [x] 将规划改造成“Spring AI Alibaba StateGraph 固定路由 + Harness 可靠执行 + SAT/UNSAT 人机闭环”；
- [x] 将 Tool/MCP 与 RAG 改为模型按需选择，并保存决策与 Action 检查点；
- [x] 冻结首次创建的执行预算，恢复时只合并旅行约束并保留已消耗额度；
- [x] 实现暂停、恢复、取消、节点重试和预算控制；
- [x] 使用 Redis Streams + SSE 推送进度。

完成标志：Worker 重启后任务能够恢复，HTTP 不再等待完整规划。动态 Agent 代码与静态一致性检查已完成；当前机器仅有 Java 8，而项目要求 Java 21，因此本次改造后的 Maven 回归测试尚未执行。Docker daemon 也未启动，真实中间件整链路故障演练留待环境可用时执行。

### Phase 3：检索与知识闭环

- [x] 引入 Elasticsearch；
- [x] 将 JVM BM25 迁移到 ES；
- [x] 实现 PGVector + ES + RRF；
- [x] 社区内容通过 Outbox/MQ 增量索引；
- [x] 实现删除、版本、对账和全量重建；
- [x] 建立 RAG 离线评测集。

完成标志：索引可重建、可追溯、可评测，多实例无内存索引分裂。代码、单元测试与 100 条评测集已完成；当前机器 Docker daemon 不可用，MySQL/ES/PGVector/RabbitMQ 真实整链路测试待 Docker 可用后执行，禁止在此之前声明线上质量提升数字。

### Phase 4：Tool/MCP 与高并发治理

  - [x] 建立 Tool Gateway；
  - [x] 统一超时、重试、缓存、错误码和审计；
  - [x] 引入 Sentinel 模型/工具/用户维度限流；
  - [x] 使用 Redisson 处理必要的跨实例互斥；
  - [x] 生产禁用通用 Shell/FileSystem Tool；
  - [x] 远程 MCP 完成身份认证和 Schema 版本化。

  已落地：所有 Spring AI ToolCallback 统一经过权限与 Schema 校验、隔离线程池、连接/读取/总体超时、仅幂等重试、Redis 双层缓存、Redisson 防击穿、Sentinel 限流熔断和 MySQL 审计；审计接口输出成功率、缓存/降级率、P50/P95 与估算成本。远程 MCP 默认关闭，生产通过 Bearer Token、服务端版本约束、工具白名单和 Schema 哈希登记启用。Redis 锁不可用时绕过缓存互斥，仍由有界线程池和 Sentinel 保护主链路。

完成标志：第三方故障不会拖垮 Worker，工具权限最小化。

### Phase 5：可观测、高可用与发布

- [x] 接入 Actuator、Micrometer、Prometheus、Grafana、OpenTelemetry Collector 与 Tempo；
- [x] 建立 Agent、RAG、Tool、成本看板和告警；
- [x] 将同一镜像拆为 Agent API、Agent Worker、Knowledge Worker 三种部署角色；
- [x] 完成 Nacos 适用性评估；当前采用 Kubernetes Service/DNS + RabbitMQ，不重复引入注册中心；
- [x] 编写 Kubernetes Helm Chart，并完成 `helm lint` 与模板渲染校验；
- [x] 编写滚动发布、备份恢复脚本和故障演练 Runbook；
- [ ] 在具备 Docker/Kubernetes 的隔离环境执行多副本、备份恢复和故障注入，记录真实 RPO/RTO。

完成标志：代码、配置、Chart 和运维流程已落地；单应用副本故障不丢任务及 RPO/RTO 仍须通过真实环境演练验证，验证前不得写成已达成指标。

### Phase 6：压测、评测与简历材料

- [x] 编写 API、SSE、MQ、RAG、Agent Worker 黑盒压测工具，统一输出 JSON/Markdown 报告；
- [x] 完成 10 组上下文压缩离线实验并保留失败迭代记录；
- [x] 编写基于 100 条冻结数据集的 BM25/向量/RRF 三实例消融工具；
- [x] 输出当前架构图、异步任务/知识闭环时序图和故障演练报告结构；
- [x] 按证据等级整理简历项目职责与能力矩阵；
- [x] 准备技术选型、取舍和失败案例面试话术；
- [ ] 在隔离 Docker/Kubernetes 环境执行正式 API/SSE/MQ/Worker 压测和 RAG 消融，填写真实性能指标；
- [ ] 执行故障注入并填写 RPO/RTO、Trace 和 Prometheus 原始证据。

完成标志：评测框架、离线压缩实验、图表和简历证据链已落地；基础设施性能与 RPO/RTO 仍为 `NOT_MEASURED`，完成真实环境实验后才能写入简历数字。

---

## 20. 优先级与依赖关系

```text
数据库基线
   -> 记忆外置
   -> 异步任务
   -> Harness检查点
   -> SSE事件

数据库基线
   -> Outbox
   -> RabbitMQ
   -> ES/PGVector索引闭环

Harness + Tool Gateway
   -> Sentinel治理
   -> 可观测
   -> 多副本部署
   -> 压测与故障演练
```

最高优先级：

1. 文件记忆迁移；
2. 任务与检查点模型；
3. RabbitMQ 异步规划；
4. 上下文预算和摘要；
5. Tool 超时/重试/熔断；
6. JVM 索引外置。

暂缓项：

- 一开始拆大量微服务；
- 在无跨库强一致需求时引入 Seata；
- 在无标注数据时引入复杂 Learning-to-Rank；
- 同时引入多个 Agent 框架；
- 为展示技术自行运维复杂的生产级状态中间件集群。

---

## 21. 项目级 Definition of Done

完成本轮改造必须同时满足：

- [ ] 至少两个应用副本能够共同处理请求；
- [ ] 本地文件和 JVM 内存不再承载不可丢失状态；
- [ ] 旅行规划使用异步任务，有任务 ID、状态、进度和最终结果；
- [ ] Worker 崩溃后能够重试或从检查点恢复；
- [ ] Chat Memory、Chat History、Summary、Profile、Long-term Memory 分层实现；
- [ ] 上下文有 Token 预算、滚动摘要、去重和硬约束保护；
- [ ] RAG 支持 ES + PGVector 混合召回、RRF、引用和离线评测；
- [ ] 社区到知识库使用 Outbox + MQ，支持幂等、删除、对账和重建；
- [ ] Tool/MCP 有权限、Schema、超时、重试、缓存、熔断和审计；
- [ ] RabbitMQ、Redis、数据库故障均有明确恢复策略；
- [ ] Prometheus/Grafana 能观察性能、质量、可靠性和成本；
- [ ] 有压测、RAG 评测和故障演练报告；
- [ ] 简历中只写已经完成并能解释、演示、复现的能力。

---

## 22. 官方资料与选型依据

- [Spring AI Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)：区分 Chat Memory 与完整 Chat History，支持窗口记忆及 JDBC/Redis 等 Repository。
- [Spring AI Chat Client](https://docs.spring.io/spring-ai/reference/api/chatclient.html)：ChatClient、Advisor、Tool Calling 和 Memory 的组合方式。
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)：工具循环、动态工具、风险工具最小暴露和自定义 Tool Advisor。
- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/)：ChatClient、Advisor、Model、VectorStore 和 Tool 的 Micrometer 观测能力。
- [RabbitMQ Quorum Queues](https://www.rabbitmq.com/docs/quorum-queues)：关键队列的复制、安全确认和适用边界。
- [RabbitMQ Publisher Confirms and Consumer Acknowledgements](https://www.rabbitmq.com/docs/confirms)：可靠发布和消费确认机制。
- [Redis Streams](https://redis.io/docs/latest/develop/data-types/streams/)：事件追加、消费者组、确认、Pending 和回放能力及持久化边界。
- [Elasticsearch Hybrid Search](https://www.elastic.co/docs/solutions/search/hybrid-search)：关键词与语义检索融合，官方推荐从 RRF 开始。
- [Elasticsearch RRF](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion)：异构召回排名融合方法。
- [Spring Cloud Alibaba Nacos](https://sca.aliyun.com/en/docs/2025.x/user-guide/nacos/quick-start/)：服务发现与配置管理。
- [Spring Cloud Alibaba Sentinel](https://sca.aliyun.com/en/docs/2022/user-guide/sentinel/overview/)：流控、熔断降级和系统保护。

> 注意：官方文档可能展示比当前项目更新的 Spring AI API。实现前必须以项目最终选定的 BOM 版本对应文档为准，禁止直接复制跨大版本示例。
