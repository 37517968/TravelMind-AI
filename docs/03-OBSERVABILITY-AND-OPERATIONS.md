# 03 可观测、部署与运维

## 1. 三层可观测模型

| 层次 | 技术与存储 | 回答的问题 |
|---|---|---|
| Metrics | Micrometer -> Prometheus -> Grafana/Alertmanager | 整体是否变慢、失败率是否升高、队列和资源是否积压 |
| Trace | OpenTelemetry -> Collector -> Tempo | 一次执行在哪个 HTTP/MQ/节点/RAG/Tool 上耗时或报错 |
| Business Run | MySQL execution/checkpoint/tool audit -> Run Explorer | taskId 实际走了哪些节点、输入输出、重试和恢复版本 |

Prometheus 只使用角色、状态、节点、Tool 和有限错误码等低基数标签。`taskId/requestId/messageId` 进入 Trace 或 MDC，`userId/conversationId/Prompt/Completion/Tool 密钥` 不进入指标。

## 2. 指标目录

| 领域 | 指标 | 含义 |
|---|---|---|
| HTTP | `http_server_requests_seconds_*` | 请求量、错误率、P95/P99 |
| Task | `agent_task_submitted_total` | 新建、幂等和并发命中 |
| Task | `agent_task_duration_seconds_*`、`agent_task_completed_total` | Worker 执行耗时和结局 |
| Queue | `agent_task_queue_delay_seconds_*` | 命令等待 Worker 的时间 |
| E2E | `agent_task_end_to_end_seconds_*` | 创建到终态 |
| State | `agent_tasks{status}`、`agent_outbox_backlog` | 状态存量和待发布事件 |
| Workflow | `agent_node_duration_seconds_*`、`agent_node_completed_total` | 节点耗时和结果 |
| Route | `agent_workflow_route_total` | CONTINUE/WAITING/SAT/UNSAT 等分支 |
| Model | `agent_model_first_token_duration_seconds_*` | 首 Token 延迟 |
| Model | `agent_model_calls_total`、`agent_model_tokens_total` | 按节点估算调用和 Token |
| SSE | `agent_sse_connections_active`、`agent_sse_connections_total`、`agent_sse_events_total` | 活跃连接、打开/关闭和事件类型 |
| RAG | `rag_search_duration_seconds_*`、`rag_search_total` | 命中、空结果、缓存和降级 |
| Tool | `tool_calls_total`、`tool_duration_seconds_*` | 工具成功、错误码、缓存、降级和耗时 |
| Cost | `tool_estimated_cost_total` | 外部工具成本代理，不等于账单 |
| Knowledge | `knowledge_index_failures` | 待修复索引状态 |
| Recovery | `agent_task_recovered_total` | 过期任务恢复次数 |
| RabbitMQ | exporter 指标 | ready/unacked、消费者和消息速率 |

数据库型 Gauge 每 15 秒读取并在进程内缓存，避免每次 Prometheus scrape 都触发 SQL。多副本事实型 Gauge 使用 `max`，Counter/Timer 使用 `sum(rate(...))`。

## 3. Trace 传播

1. HTTP 入口产生 W3C `traceparent`；
2. 任务事务把父上下文写入 Outbox；
3. Outbox Publisher 恢复上下文并创建 Producer Span；
4. Spring AMQP 把上下文注入 RabbitMQ Message；
5. Worker 创建任务、节点、RAG、模型和 Tool Span；
6. 重试消息复制 `traceparent/tracestate`；
7. 用户 resume 会产生新 execution，通过同一 `taskId` 聚合。

Tempo 查询示例：

```traceql
{ span."agent.task.id" = "1001" }
```

错误节点：

```traceql
{ span:name = "agent.workflow.node" && span."agent.task.id" = "1001" && span:status = error }
```

应用端启用 Collector 尾采样时采样率必须设为 1.0；Collector 完整保留错误、慢 Trace 和异常任务结果，只抽样正常短 Trace。Tempo 的当前单进程本地存储只适合开发和单机验证。

## 4. Run Explorer

- 用户地址：`/observability/{taskId}`；
- 当前用户只能查看自己的任务；管理员可以使用管理接口；
- 页面展示多次 execution、traceId、节点、路由、耗时、重试和 Tool；
- 点击节点时按需读取 Checkpoint 的 Input/Output/State；
- 后端递归脱敏密码、Token、Cookie、API Key 等字段，并限制 JSON 深度、数组项和文本长度；
- 旧任务没有 execution 时仍可从历史 Checkpoint 和 Tool Audit 构建路径。

MySQL Run Explorer 是业务执行事实源；Tempo 是技术调用链。正常 Trace 可能因采样不存在，但业务运行记录仍应可查。

## 5. 看板访问与部署

启动：

```bash
docker compose --env-file .env --profile observability up -d --build
```

默认管理端口只绑定服务器回环：

| 服务 | 地址 |
|---|---|
| Grafana | `127.0.0.1:3000` |
| Prometheus | `127.0.0.1:9091` |
| Alertmanager | `127.0.0.1:9093` |
| RabbitMQ 管理台 | `127.0.0.1:15672` |

本地电脑访问服务器看板：

```bash
ssh \
  -L 3000:127.0.0.1:3000 \
  -L 9091:127.0.0.1:9091 \
  -L 15672:127.0.0.1:15672 \
  user@server
```

浏览器打开 `http://localhost:3000`。不要直接把管理端口暴露公网；确需暴露时必须配合防火墙、来源 IP、TLS 和强密码。

## 6. 三运行角色与发布

| 角色 | 职责 | 不负责 |
|---|---|---|
| Agent API | HTTP、SSE、登录、任务写入和查询 | 不消费 Agent/Knowledge 队列 |
| Agent Worker | Outbox、Agent Queue、StateGraph、恢复 | 不承接公网流量 |
| Knowledge Worker | 知识 Queue、Embedding、索引和对账 | 不与在线规划共享消费线程 |

同一不可变镜像通过环境变量切换角色。当前服务器用 Docker Compose；Helm Chart 描述 Kubernetes 目标形态。

### 6.1 滚动发布顺序

1. Flyway 遵循 Expand/Contract，先加兼容结构，旧实例退出后才移除旧结构；
2. 执行测试、镜像/依赖/密钥扫描和 `helm template`；
3. 先灰度一个 Agent Worker，观察失败率、P95、DLQ、Outbox；
4. 再发布 Knowledge Worker，检查索引失败和 RAG 降级；
5. 最后滚动 Agent API，目标形态 `maxUnavailable=0`；
6. SIGTERM 后 readiness 退出，Rabbit Listener 停止取新消息，在宽限期内完成 Checkpoint/ACK；
7. 应用可以回滚，数据库使用新的前向修复迁移，不依赖破坏性逆转。

### 6.2 扩缩容

- API：按 CPU/请求率扩展，目标至少两个副本；
- Agent Worker：优先按 Rabbit QueueLength 扩展，缩容必须保留稳定窗口，防止处理中任务抖动；
- Knowledge Worker：独立资源池，避免 Embedding 初始化和在线规划争抢；
- 扩 Worker 前先确认模型与高德配额，否则只会把内部排队转化为供应商限流。

## 7. 高可用边界

| 组件 | 已有设计 | 尚未证明 |
|---|---|---|
| API | 无状态、多副本配置、Redis Session、探针和优雅停机 | 当前正式环境多副本可用率 |
| Worker | 至少一次投递、幂等抢占、Checkpoint 恢复 | 故障恢复成功率与 RTO |
| RabbitMQ | Quorum Queue 配置、持久消息、Outbox/DLQ | Compose 单节点不具备三节点 Broker HA |
| Redis | 短期状态可降级、最终结果不依赖 Redis | 托管主备/Cluster 故障切换 |
| MySQL/PG/ES | 备份和可重建索引策略 | 多节点切换、具体 RPO/RTO |
| Tempo | 单机验证 | 对象存储、鉴权和高可用部署 |

不能把“具备高可用设计”说成“已经达到 99.99% 可用率”。

## 8. 备份与恢复

- MySQL：每日全量 + PITR/binlog，Outbox 必须随事实数据备份；
- PGVector：`pg_dump -Fc`，向量索引也可从 MySQL 知识事实重建；
- Redis：按业务等级使用 AOF/RDB，Redis 内容不能替代最终结果；
- Elasticsearch：Snapshot Repository，同时验证从 MySQL 蓝绿重建；
- RabbitMQ：不是长期备份，依靠 Quorum、持久消息、Outbox 和 DLQ。

恢复顺序：在隔离空目标校验哈希 -> 恢复 MySQL/PG -> 恢复 Redis -> 启动单 Knowledge Worker 对账/重建 -> 启动 Rabbit/Agent Worker 观察 Outbox 补发 -> 抽检任务、Checkpoint、社区与引用 -> 逐步放量。

恢复脚本要求显式 `CONFIRM_RESTORE=RESTORE_TO_EMPTY_TARGET`，禁止未确认覆盖生产目标。没有实际演练数据前，RPO/RTO 只能写 `NOT_MEASURED`。

## 9. 故障演练矩阵

| 场景 | 预期 | 证据 |
|---|---|---|
| API 实例退出 | 其他 API 接管，SSE 通过 Last-Event-ID 续读 | 可用率、事件 ID |
| Worker 退出 | 未 ACK 消息重投，租约扫描后从 Checkpoint 恢复 | recovered、checkpoint、trace |
| RabbitMQ 短时不可用 | 业务事务成功，Outbox 积压后补发 | backlog、confirm |
| Redis 重启 | 缓存/锁/实时事件降级，MySQL 结果不丢 | degraded、结果查询 |
| ES 或 PG 单路不可用 | Hybrid RAG 单路降级 | RAG degraded、warning |
| Tool 超时/5xx | 隔离、超时、有限重试、stale fallback | error_code、P95、Trace |
| 模型超时 | 节点失败、重试或 DLQ | node span、retry/DLQ |
| 索引消息乱序 | 旧版本不覆盖新版本 | knowledge_index_state |

当前主要是设计与自动化覆盖，多节点故障演练尚未形成正式 RPO/RTO 报告。

## 10. 上线检查

1. `docker compose ps` 确认 API/Worker/存储健康；
2. Prometheus `up` 检查应用角色、RabbitMQ、Redis exporter；
3. 提交一个任务，确认 taskId、SSE、最终结果和 Run Explorer；
4. Tempo 按 traceId 搜索 HTTP -> Outbox -> MQ -> Worker -> Node -> Tool/RAG；
5. 人工制造受控 Tool 超时，核对 Trace、指标和告警；
6. 检查日志、Trace 和指标没有 Prompt、Completion、密钥和用户标识；
7. Alertmanager 接入真实通知渠道，空接收器不能作为上线完成证据。
