# 架构决策记录（ADR）

ADR 用来记录“为什么这样设计”，避免几个月后只看到代码，却不知道当时的约束、备选方案和取舍。

状态说明：

- `Proposed`：正在讨论；
- `Accepted`：当前采用；
- `Superseded`：已被后续 ADR 替代；
- `Rejected`：评估后未采用。

新增决策时复制以下结构并递增编号：背景、决策、理由、后果、替代方案、复审条件。

当前决策：

- [当前平台架构、完整请求链路、记忆与上下文管理](CURRENT-PLATFORM-ARCHITECTURE.md)
- [ADR-0001：先模块化单体，后按压力拆分](ADR-0001-modular-monolith-first.md)
- [ADR-0002：按生命周期分层存储 Agent 记忆](ADR-0002-tiered-agent-memory.md)
- [ADR-0003：使用 RabbitMQ 和 Outbox 执行可靠异步任务](ADR-0003-rabbitmq-outbox.md)
- [ADR-0004：使用 Elasticsearch 与 PGVector 混合检索](ADR-0004-hybrid-retrieval.md)
- [ADR-0005：Kubernetes 环境不额外引入 Nacos](ADR-0005-kubernetes-service-discovery-without-nacos.md)
- [Phase 2：异步任务与 Agent Harness](PHASE-2-ASYNC-HARNESS.md)
- [Phase 5：可观测性说明](PHASE5-OBSERVABILITY.md)
- [Phase 5：部署、发布与高可用 Runbook](PHASE5-DEPLOYMENT-RUNBOOK.md)
- [Phase 5：数据备份与恢复 Runbook](PHASE5-BACKUP-RESTORE.md)
- [Phase 5：故障演练清单](PHASE5-FAILURE-DRILL.md)
- [Phase 6：当前架构图与关键时序](PHASE6-ARCHITECTURE-AND-SEQUENCE.md)
- [Phase 6：简历项目材料](PHASE6-RESUME-PROJECT.md)
- [Phase 6：简历能力证据矩阵](PHASE6-EVIDENCE-MATRIX.md)
- [Phase 6：技术选型与面试话术](PHASE6-INTERVIEW-GUIDE.md)
