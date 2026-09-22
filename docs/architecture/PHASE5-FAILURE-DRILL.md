# 故障演练清单

| 场景 | 操作 | 预期结果 | 关键证据 |
|---|---|---|---|
| API Pod 退出 | 删除一个 API Pod | Service 继续可用，SSE 按 Last-Event-ID 重连 | 可用率、事件 ID |
| Agent Worker 退出 | 在节点执行中删除 Pod | 未 ACK 消息重分配，租约超时后从检查点恢复 | recovered 指标、checkpoint |
| RabbitMQ 短时不可用 | 阻断 Publisher | 业务事务成功，Outbox 积压并在恢复后补发 | Outbox、Publisher Confirm |
| Redis 重启 | 重启 Redis 主节点 | Tool 缓存/锁降级，不破坏 MySQL 最终结果 | Tool degraded、结果查询 |
| ES 不可用 | 阻断 ES | RAG 使用 PGVector 单路召回并标记 degraded | RAG 指标 |
| PGVector 不可用 | 阻断 PostgreSQL | RAG 使用 BM25 单路召回 | warning、RAG 指标 |
| Tool 5xx/超时 | 注入上游故障 | 隔离线程池、超时、幂等重试、stale fallback | error_code/P95/Trace |
| 模型超时 | 注入 ChatModel 延迟 | 节点超时、检查点失败、任务重试或 DLQ | node span、retry/DLQ |
| 索引消息乱序 | 高版本后投旧版本 | 索引版本不回退 | knowledge_index_state |

每次演练必须保存时间、命令、Prometheus 原始查询、Trace ID、数据库状态和结论。不得把“设计上可恢复”写成“已完成故障演练”。
