# 行程智控——Agent 旅行决策与智能规划平台

## 推荐项目描述

面向复杂旅行决策场景的异步 Agent 平台。系统以显式 Harness 状态图编排行程规划流程，融合混合检索、天气/地图/搜索 Tool 与社区知识闭环；通过持久化任务、工作流检查点、Transactional Outbox、RabbitMQ 和 Redis Stream 支持任务恢复、流式输出与多副本部署，并建立指标、Trace、告警和 Helm 发布体系。

## 技术栈

Spring Boot、Spring AI、WebFlux/Reactor、MySQL、Redis/Redis Stream、RabbitMQ Quorum Queue、Elasticsearch、PGVector、RRF、Sentinel、Redisson、OpenTelemetry、Micrometer、Prometheus、Grafana、Tempo、Docker、Kubernetes、Helm。

## 推荐职责表述

1. 负责异步 Agent 任务平台设计与实现，基于 `Idempotency-Key` 保证提交幂等，在同一 MySQL 事务内写入任务与 Transactional Outbox，通过 RabbitMQ 持久消息、Publisher Confirm、手动 ACK、延迟重试和 DLQ 构建可靠任务链路。
2. 将旅行规划重构为显式 Harness 状态图，拆分需求校验、上下文构建、RAG、天气/POI、预算、生成和结果校验等节点，实现并行增强、节点超时/重试、预算控制、暂停恢复及 MySQL Checkpoint 故障续跑。
3. 基于 Redis Stream 为节点进度与模型 Token Chunk 分配有序消息 ID，通过 SSE 和 `Last-Event-ID` 支持实时输出与断线续传；最终结果由 MySQL 保存为事实记录，区分实时事件、短期记忆和业务结果的存储职责。
4. 建设 Elasticsearch BM25 + PGVector 语义召回 + RRF 融合的混合检索链路，使用版本化 Outbox 事件完成社区内容增量索引、删除、乱序幂等、对账和蓝绿重建，并建立 100 条多类型冻结评测集及消融脚本。
5. 构建统一 Tool/MCP Gateway，集中处理 Schema 校验、权限、超时、有界线程池、仅幂等重试、Redis 缓存、Redisson 防击穿、Sentinel 限流熔断和 MySQL 审计，避免第三方依赖故障扩散到 Agent Worker。
6. 建立 Agent、Workflow、RAG、Tool、Outbox 和模型用量可观测体系，使用 Micrometer/OpenTelemetry 关联 HTTP、MQ、节点和工具调用，预置 Prometheus 告警、Grafana 看板与 Tempo Trace，并控制高基数标签及 Prompt/Completion 隐私。
7. 使用同一镜像拆分 Agent API、Agent Worker、Knowledge Worker 三种运行角色，编写 Helm Chart 配置滚动更新、readiness/liveness/startup Probe、优雅停机、HPA/PDB 和可选 KEDA，配套备份恢复与故障演练 Runbook。

## 可选的真实离线指标

仅在能够解释实验边界时使用：

> 建立 10 组上下文压缩离线样例，在字符级原型实验中将上下文减少 34%，硬约束与最近轮次保持率均为 100%；同时保留首次实现导致上下文膨胀的失败记录。

不得写成“Token 降低 34%”或“线上成本降低 34%”，因为当前实验没有使用 Qwen Tokenizer，也未接入生产流量。

## 暂时不能写的数字

- API QPS、P95/P99；
- SSE 最大连接数；
- Worker 每分钟完成任务数；
- MQ 零丢失或故障恢复时间；
- RRF 相对纯 BM25/向量的提升比例；
- 线上 Token/成本下降比例；
- RPO/RTO。

这些数字必须来自 `docs/benchmark/` 中具备环境、数据规模、脚本和原始结果的正式报告。
