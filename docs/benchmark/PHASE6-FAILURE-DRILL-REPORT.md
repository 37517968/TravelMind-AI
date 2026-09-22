# Phase 6 故障演练报告

## 当前结论

状态：`NOT_EXECUTED`。

当前机器 Docker daemon 不可用，因此本文件只建立证据结构，不声明已通过故障演练，也不填写虚构 RPO/RTO。执行步骤参见 [Phase 5 故障演练清单](../architecture/PHASE5-FAILURE-DRILL.md)。

## 执行记录

| 场景 | 执行时间 | 版本/环境 | 结果 | RPO | RTO | Trace/指标证据 |
|---|---|---|---|---:|---:|---|
| API Pod 退出 | 待执行 | | NOT_MEASURED | | | |
| Agent Worker 中断 | 待执行 | | NOT_MEASURED | | | |
| RabbitMQ 短时不可用 | 待执行 | | NOT_MEASURED | | | |
| Redis 主节点切换 | 待执行 | | NOT_MEASURED | | | |
| Elasticsearch 不可用 | 待执行 | | NOT_MEASURED | | | |
| PGVector 不可用 | 待执行 | | NOT_MEASURED | | | |
| Tool 5xx/超时 | 待执行 | | NOT_MEASURED | | | |
| 模型超时 | 待执行 | | NOT_MEASURED | | | |

## 每次演练必须附带

- 完整命令、开始/恢复时间和操作者；
- Prometheus 查询及原始数据；
- Trace ID、任务 ID、Checkpoint 和 Outbox/DLQ 状态；
- 用户可见影响、数据丢失检查和重复执行检查；
- 根因、短期处置、长期改进和复测结果。
