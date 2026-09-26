# 行程智控——Agent 旅行决策与智能规划平台（简历版）

## 项目描述

面向多轮旅行咨询与复杂行程决策的异步 Agent 平台。系统以声明式 StateGraph 和自研 Harness 编排意图识别、约束抽取、混合检索、POI/路线工具、确定性求解、流式生成与结果校验；通过 MySQL Checkpoint、Transactional Outbox、RabbitMQ、Redis Stream 支持任务恢复、可靠投递和 SSE 断线续传，并提供多用户、多会话隔离及 Agent 全链路可观测能力。

## 技术栈

Java 21、Spring Boot、Spring AI、Spring AI Alibaba Graph、WebFlux/Reactor、MyBatis-Plus、MySQL、Redis/Redis Stream、RabbitMQ Quorum Queue、Elasticsearch、PGVector、RRF、Sentinel、Redisson、OpenTelemetry、Micrometer、Prometheus、Grafana、Tempo、Docker Compose、Kubernetes、Helm。

## 工作职责（推荐 6 条版本）

1. 设计异步 Agent 任务平台，以 `Idempotency-Key + MySQL 唯一索引` 保证提交幂等，在同一本地事务内写入 `agent_task` 与 Transactional Outbox；结合 RabbitMQ Publisher Confirm、持久消息、手动 ACK、延迟重试和 DLQ，解决数据库提交与消息发布的双写可靠性问题。
2. 将旅行规划实现为显式 StateGraph，拆分意图路由、约束抽取、RAG、候选检索、路线选择、约束求解、地图规划、流式生成、确定性校验和时效校验等节点；自研 Harness 提供节点超时/重试、执行预算、Checkpoint、暂停追问和故障续跑能力。
3. 基于 Redis Stream 保存节点进度和模型 Token Chunk，以 SSE + `Last-Event-ID` 支持实时输出与断线续传；最终结果落 MySQL 作为业务事实源，区分实时事件、短期对话记忆和最终业务结果的职责与生命周期。
4. 建设 Elasticsearch BM25 + PGVector 语义召回 + RRF 融合的混合检索链路，通过版本化知识事件完成社区方案/评论的异步切片、质量过滤、PII 脱敏、增量索引、乱序幂等、对账、DLQ 重放和蓝绿重建。
5. 构建统一 Tool/MCP Gateway，接入高德 MCP 的 POI 搜索、周边搜索、详情与驾车/步行/骑行/公交路线能力，集中实现权限、JSON Schema、超时、隔离线程池、幂等重试、Redis 缓存、Redisson 防击穿、Sentinel 限流熔断及 MySQL 审计。
6. 建立 Agent 可观测体系：Micrometer/Prometheus 统计任务成功率、端到端与排队延迟、节点 P95、首 Token、模型调用/Token、工作流分支、RAG 命中/降级及 Tool 错误；OpenTelemetry/Tempo 关联 HTTP、Outbox、MQ、Worker、节点和 Tool Span，并提供按 `taskId` 回放节点输入输出的 Run Explorer。

## 可替换的扩展职责

- 实现用户注册登录和 Redis Spring Session，使用 `userId + conversationId` 完成任务、短期记忆、规划草稿和会话的双层隔离；仅将用户显式确认的旅行偏好写入 MySQL，在同一用户的不同会话间共享。
- 设计景点路线选择门：只给城市时根据 RAG/POI 生成多条候选路线，由 LLM 按真实景点组合动态命名；用户选中后把全部景点转为硬约束并逐一核验，避免最终方案静默遗漏或被同名周边 POI 替换。
- 将同一应用镜像拆成 Agent API、Agent Worker、Knowledge Worker 三种运行角色，提供 Docker Compose 和 Helm 部署，配置 readiness/liveness/startup Probe、优雅停机、HPA/PDB 及可选 KEDA 扩缩容。

## 面试时必须保持准确的边界

- 当前是“固定工作流 + 条件路由 + 节点内按约束调用 Tool/RAG”，不是完全开放式 ReAct，也不允许模型生成代码直接执行。
- Docker Compose 单 RabbitMQ 节点只能验证 Quorum Queue 配置，不能宣称已完成三节点 Broker 高可用实测。
- 当前完成了 Redis 有界短期记忆、会话 PlanningDraft、MySQL Checkpoint 和显式用户偏好；尚未完成 MySQL 全量聊天归档、滚动摘要和向量长期记忆。
- 不能填写未经正式报告验证的 QPS、P95/P99、零丢失、可用率、RPO/RTO 和 RAG 提升比例；数字必须来自 `docs/benchmark/` 的可复现实验。

## 30 秒项目介绍

这是一个把大模型能力放进可靠异步任务系统的旅行规划 Agent。请求进入后先持久化任务和 Outbox，再由 RabbitMQ 驱动 Worker 执行固定 StateGraph；流程中通过 RAG 和高德 MCP 获取知识与实时 POI，以确定性求解器约束预算和必选景点，最终由模型生成方案并进行规则校验。执行过程用 Checkpoint 恢复、Redis Stream/SSE 流式返回，并用 Prometheus、Tempo 和 Run Explorer 同时解决聚合监控、技术 Trace 与业务路径回放。
