# Phase 5：部署、发布与高可用 Runbook

## 运行单元

同一个不可变镜像通过环境变量拆为三个运行角色：

| 角色 | 副本 | 职责 | 禁止职责 |
|---|---:|---|---|
| Agent API | ≥2 | HTTP、SSE、任务写入、查询 | 不消费 Agent/Knowledge 队列 |
| Agent Worker | ≥2 | Outbox 发布、Agent Queue、Harness、检查点恢复 | 不承接公网流量 |
| Knowledge Worker | 独立资源池 | 知识 Queue、Embedding、对账、兜底同步 | 不与在线规划争抢线程池 |

Kubernetes Service 只把业务流量送到 Agent API。Worker 只暴露内部 Management Service。Kubernetes 已提供服务发现，因此本阶段不引入 Nacos；若以后存在跨集群、非 Kubernetes 服务注册或动态配置中心需求，再单独做选型。

## 滚动发布

1. Flyway 迁移必须遵循 Expand/Contract：先增加兼容字段/表，所有旧 Pod 下线后才删除旧结构。
2. 发布前执行单元测试、镜像/依赖/密钥扫描和 `helm template`。
3. 先发布一个 Agent Worker，观察任务失败率、P95、DLQ 和 Outbox 10 分钟。
4. 再发布 Knowledge Worker；检查索引失败数、RAG 降级率和对账结果。
5. 最后滚动 Agent API。API 使用 `maxUnavailable=0`，至少保留一个就绪副本。
6. Pod 收到 SIGTERM 后 readiness 退出，`preStop` 等待 10 秒；Spring graceful shutdown 最多等待 60 秒，Pod 总宽限期 90 秒。Rabbit Listener 停止取新消息，当前节点在 ACK 前完成检查点。
7. 指标异常时使用 `helm rollback <release> <revision>`。数据库迁移不得依靠破坏性逆转，应走新的前向修复迁移。

## 验证命令

```bash
helm lint deploy/helm/travelmind-ai
helm template travel deploy/helm/travelmind-ai > rendered.yaml
kubectl rollout status deployment/travel-travelmind-ai-api
kubectl get pods -l app.kubernetes.io/instance=travel
```

## 高可用边界

- 应用：无状态 API、多 Agent Worker、幂等消费、MySQL 检查点恢复。
- RabbitMQ：生产使用 3 节点 Quorum Queue；Compose 单节点只用于开发，不能宣称 Broker 高可用。
- Redis：生产使用托管主备/Cluster，并配置与 SSE 续传窗口匹配的内存和持久化。
- MySQL、PostgreSQL/PGVector、Elasticsearch：优先托管高可用；本 Chart 不在业务命名空间自建状态集群。
- Redis ChatMemory 是有 TTL 的短期上下文；MySQL `agent_task.result_json` 是最终结果事实记录，二者不是重复职责。

## 扩缩容

- API：默认 CPU HPA，最少 2 副本。
- Agent Worker：未安装 KEDA 时使用 CPU HPA；安装 KEDA 后按 `agent.plan.q` QueueLength 扩缩，缩容稳定窗口 10 分钟。
- Knowledge Worker：默认固定副本和独立资源配额，避免频繁扩缩导致 Embedding 重复初始化。

## 发布验收

- 连续提交任务时删除一个 API Pod，请求仍能提交和查询。
- 运行中删除一个 Agent Worker，未 ACK 消息重新投递，任务从 MySQL 检查点恢复。
- 滚动升级期间监控任务结果、Outbox、RabbitMQ DLQ 和 P95。
- 确认生产配置没有 Windows 路径、STDIO MCP 和本地文件会话状态。
