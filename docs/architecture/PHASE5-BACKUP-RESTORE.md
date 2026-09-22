# 数据备份与恢复 Runbook

## 备份策略

- MySQL：每日全量 + 托管实例 PITR/binlog，保存任务、检查点、Outbox 和社区事实数据。
- PostgreSQL/PGVector：每日 `pg_dump -Fc`；向量索引也可从 MySQL 知识事实表重建。
- Redis：开启与业务等级匹配的 AOF/RDB；ChatMemory、SSE Stream 和缓存不能替代 MySQL 最终结果。
- Elasticsearch：使用 Snapshot Repository；ES 是派生索引，同时验证从 MySQL 全量重建。
- RabbitMQ：消息不是长期备份；依靠 Quorum Queue、持久消息、Outbox 和 DLQ。Outbox 必须随 MySQL 备份。

`deploy/scripts/backup-datastores.sh` 生成 MySQL、PGVector、Redis 快照和 SHA-256 清单。脚本应运行在包含对应客户端的受控运维 Job 中，凭证通过 Secret 注入。

## 恢复顺序

1. 创建隔离的空目标，不直接覆盖生产实例。
2. 校验 SHA-256，恢复 MySQL 和 PGVector。
3. Redis RDB 通过托管服务快照能力或停机维护窗口导入，禁止在线替换主节点数据目录。
4. 启动单个 Knowledge Worker，执行知识对账，必要时蓝绿全量重建。
5. 启动 RabbitMQ/Agent Worker，观察 Outbox 补发和 DLQ；重复消息由任务/索引版本幂等处理。
6. 验证随机任务、检查点、社区方案和引用版本后逐步放量。

`restore-relational.sh` 要求显式设置 `CONFIRM_RESTORE=RESTORE_TO_EMPTY_TARGET`，只面向空的隔离恢复目标。

## 演练记录模板

| 项目 | 记录 |
|---|---|
| 备份时间与版本 | |
| 恢复目标 | |
| RPO / RTO 实测 | |
| MySQL 行数抽检 | |
| PGVector/ES 对账 | |
| Outbox/DLQ 状态 | |
| 任务结果抽检 | |
| 问题与改进 | |

当前仓库只提供可执行脚本和流程；必须在具备 Docker/Kubernetes 与隔离数据卷的环境实际演练后，才能在简历中写具体 RPO/RTO。
