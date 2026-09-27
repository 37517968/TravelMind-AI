# Agent 平台压测方案

本文用于给 TravelMind 建立可复现的容量、延迟、稳定性和质量基线。压测结果只代表指定版本、硬件、模型、数据集和网络环境；未经测量的数字不得写入简历。

## 1. 为什么不能只看 HTTP QPS

`POST /api/agent/tasks` 是异步接收接口。它很快只说明 API、MySQL 和 Outbox 能接住请求，不代表 Agent 已经完成。一次真实请求至少要分别观察：

```text
客户端 -> API/鉴权 -> MySQL + Outbox -> RabbitMQ -> Agent Worker
       -> Workflow 节点 -> LLM / RAG / Tool/MCP -> Redis Stream -> SSE -> 客户端
```

因此需要同时测“接收能力”“消费能力”“用户体验”和“答案质量”。如果生产速度长期大于 Worker 消费速度，即使提交 P95 很低，队列仍会持续积压。

## 2. 指标体系

| 层级 | 核心指标 | 用途 |
|---|---|---|
| API | 提交 QPS、HTTP 错误率、P50/P95/P99、幂等命中率 | 判断入口容量和错误预算 |
| 异步链路 | Outbox backlog、Rabbit ready/unacked、queue delay P95、积压清空时间 | 判断消息可靠性与 Worker 是否跟得上生产速度 |
| Agent E2E | SUCCEEDED/FAILED/WAITING_USER/TIMEOUT 比例、端到端 P95/P99 | 真实业务完成能力 |
| 流式体验 | SSE 活跃连接、首事件、首 Token、流持续时间、断线重连成功率、重复/丢失事件 | 衡量用户感知延迟和恢复能力 |
| Workflow | 节点 P95、节点错误/重试率、路由分布、检查点恢复次数、预算耗尽率 | 定位编排慢点和异常路由 |
| LLM | 并发调用、TTFT、模型总耗时、输入/输出 Token、Token/请求、Token/s、限流和超时 | 判断模型性能、配额和成本 |
| Tool/MCP | 按工具的调用量、P95、失败率、限流率、缓存命中率、降级率 | 判断高德等外部依赖是否成为瓶颈 |
| RAG | 搜索 P95、空召回、降级率、缓存命中率、Recall@K、MRR、NDCG、引用正确率 | 同时约束检索性能和质量 |
| 上下文/记忆 | 上下文 Token、压缩率、硬约束保持率、Redis 延迟/内存、跨用户/跨会话泄漏数 | 防止上下文越长越慢以及隔离问题 |
| 基础设施 | CPU、内存、GC、线程池、JDBC 连接池、MySQL/Redis/ES/PGVector/Rabbit 饱和度 | 使用 USE 方法定位资源瓶颈 |
| 可靠性 | 重复执行数、DLQ、补偿成功率、恢复成功率、RTO/RPO | 验证 Outbox、幂等和故障恢复 |

当前代码已经采集 API、任务/节点耗时、队列延迟、端到端耗时、首 Token、模型调用/Token、RAG、Tool、工作流路由、Outbox 和任务状态。SSE 增加了 `agent_sse_connections_active`、`agent_sse_connections_total` 与 `agent_sse_events_total`。模型原生 Usage、Token/s、上下文分段 Token 和幂等命中率仍属于下一步增强项，报告中应标记为 `NOT_MEASURED`，不能用字符数估算冒充真实 Token。

## 3. 分层场景

按以下顺序执行，前一层不稳定时不要放大后一层：

1. **Smoke**：1 并发、1～3 次，验证认证、会话、任务、SSE 和指标链路。
2. **Baseline**：1 并发，固定 10～30 个问题，测无排队情况下的模型与工具基线。
3. **Load**：逐级 2/4/8/16 并发，每级 10～15 分钟，寻找目标负载下的稳定容量。
4. **Stress**：继续增加到 SLO 首次失守，记录饱和点，不以压垮生产环境为目标。
5. **Spike**：短时间突增任务，观察 Outbox/Rabbit 积压和恢复速度。
6. **Soak**：以稳定负载运行 2～8 小时，观察内存、连接、Redis Stream 和数据增长。
7. **Failure drill**：受控停止 Worker、RabbitMQ、Redis 或模拟 Tool 超时，验证恢复、重试和 DLQ。

RAG 性能与质量要单独运行固定评测集；否则“更快但召回变差”会被错误地判定为优化。上下文压缩同样要同时比较耗时、Token 与硬约束保持率。

## 4. 初始 SLO 门槛

以下是建立第一轮基线用的门槛，不是项目已经达到的成绩。完成三轮稳定测试后再按业务需求调整：

| 指标 | 初始门槛 |
|---|---:|
| API/流式/E2E 场景成功率 | >= 99% |
| 任务提交 P95 | <= 1 s |
| SSE 首事件 P95 | <= 2 s |
| SSE 首 Token P95 | <= 30 s |
| Agent E2E P95 | <= 300 s |
| 队列 | 停止施压后能持续下降并在约定时间内清空 |
| 数据一致性 | 重复执行、跨用户/会话串话均为 0 |

外部 LLM/MCP 限流要单独归因，既记录平台总成功率，也记录“排除供应商故障后的内部成功率”，但不能从总结果中删除失败样本。

## 5. 正式执行前提

- 使用测试环境或明确的维护窗口，不直接把生产流量当压测靶场；
- 建立专用 `loadtest` 用户，压测账号只在服务器环境变量中保存；
- 固定 Git commit、Docker 镜像、服务器规格、模型、模型配额、知识库快照和测试问题集；
- 设定本轮最大任务数、最大并发、最大运行时间和模型/地图 API 成本上限；
- 启动 `observability` profile，并确保压测机能访问 API；Prometheus 可由服务器本地脚本直接访问；
- 压测前后记录 RabbitMQ、Outbox、DLQ、任务状态和数据库行数，避免遗留任务污染下一轮。

## 6. 建议报告内容

每次报告至少写明：commit、时间、环境、模型、数据量、请求模型、预热、并发曲线、总请求数、错误分类、P50/P95/P99、各节点耗时、Token/成本、队列峰值与清空时间、资源峰值、RAG 质量，以及所有限制。原始 JSON 保存在 `target/phase6-reports/`，核验后再将正式报告复制到 `docs/benchmark/`。
