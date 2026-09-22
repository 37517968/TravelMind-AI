# 技术选型、取舍与失败案例面试话术

## 1. 为什么是 Outbox，不是只依赖 Publisher Confirm？

Confirm 只能证明 Broker 是否确认某次发送，不能原子地覆盖“数据库事务已提交、应用还没发送就崩溃”的窗口。Outbox 把业务任务和待发送事件写进同一本地事务；Publisher Confirm 成功后再标记发布。系统接受至少一次投递，再依靠 requestId、taskId、事件版本和状态条件更新实现幂等。

## 2. 为什么关键队列使用 Quorum Queue？

任务消息需要跨节点复制和故障选主，Quorum Queue 更适合重要持久消息。它不能替代 Outbox：Broker 内部复制解决的是消息进入 RabbitMQ 后的可用性，Outbox 解决数据库提交到消息发布之间的可靠衔接。本地 Compose 只有单节点，不能据此宣称 Broker 高可用。

## 3. 为什么进度用 Redis Stream，不直接用 Pub/Sub？

Pub/Sub 对离线消费者不保留消息；Stream 为每条进度和 Token Chunk 分配有序 ID，SSE 断线后可通过 `Last-Event-ID` 继续读取。Stream 只保存短期事件，最终行程仍落 MySQL，避免 Redis 淘汰导致业务结果丢失。

## 4. 为什么 MySQL 结果、ChatMemory 和 Redis Stream 不算重复存储？

- MySQL `result_json`：任务最终事实和查询结果；
- Redis ChatMemory：有 TTL 的对话窗口，服务于下一轮模型上下文；
- Redis Stream：有保留上限的传输事件，服务于实时进度和重连。

三者生命周期、读取方和一致性要求不同。

## 5. Harness 和 Harness.io 有什么区别？

项目中的 Harness 是显式工作流执行框架：状态图、节点校验、超时重试、预算、Checkpoint 和恢复。Harness.io 是 CI/CD 产品；如果未来接入，只负责发布流水线，不能混为 Agent 编排引擎。

## 6. 为什么用 ES + PGVector + RRF？

BM25 擅长专有名词、地名和精确约束，向量检索擅长语义近似。两路分数不可直接比较，因此使用基于排名的 RRF 融合，降低分数标定成本。系统同时保留单路降级和三实例消融脚本，用固定数据集比较 Recall@K、MRR、无答案准确率和延迟。

## 7. 为什么不引入 Nacos 和 Seata？

当前同步入口通过 Kubernetes Service/DNS 发现，Worker 通过 RabbitMQ 解耦，没有大量 Spring Cloud RPC；再加 Nacos 只会增加控制面。跨组件一致性采用本地事务 + Outbox + 幂等补偿，并不存在必须使用 Seata 的跨库强一致事务。出现跨集群注册或真实跨库强一致需求时再复审。

## 8. 高并发治理为什么同时使用 Sentinel 和 Redisson？

Sentinel 处理限流、热点用户、并发隔离和熔断；Redisson 只用于跨实例缓存防击穿等必要互斥。分布式锁不包围慢速模型调用，也不是主要正确性手段；任务状态机和数据库条件更新才负责业务并发正确性。

## 9. 可观测性如何避免标签爆炸和隐私泄露？

Prometheus 标签只放角色、状态、节点、Tool、有限错误码等低基数字段。requestId、taskId、conversationId 和 messageId 放到 Trace/MDC；userId 不进入指标。生产默认不记录 Prompt、Completion、RAG 正文和 Tool 参数，Trace 使用采样率控制成本。

## 10. 一个真实失败案例是什么？

第一次上下文压缩实验把长标题、完整旧历史和新结构一起放入 Envelope，结果 1500 字符被扩成约 2397 字符，压缩比例 1.598。根因是没有把结构开销纳入预算，也没有设置短会话阈值。随后改为规范化硬约束、短标签、极短历史摘要和完整最近轮次，10 个冻结样例降到 990 字符，比例 0.660，约束与最近轮次保持率 100%。这个结果只证明离线字符实验，生产仍需要模型摘要质量和真实 Tokenizer 评测。

## 11. 如何回答“项目是否真的高可用”？

准确回答是：应用层已具备无状态 API、多 Worker、检查点恢复、可靠消息、探针、优雅停机、HPA/PDB 和 Helm 发布配置；单元测试与静态部署校验已完成。但当前机器未执行多节点中间件和故障注入，因此不能声称达到具体可用率、RPO/RTO 或零丢失。下一步按故障 Runbook 采集证据。
