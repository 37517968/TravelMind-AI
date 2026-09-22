# Phase 6 离线评测报告（2026-09-21）

## 已执行项目

环境：Windows、JDK 21.0.8、Maven 3.9.9。Docker daemon 当前不可用。

### 上下文压缩原型

- 冻结样例：10 组多轮旅行约束对话；
- 原始字符数：1500；
- 压缩后字符数：990；
- 压缩比例：0.660，即字符数减少 34.0%；
- 硬约束保持率：100%；
- 最近轮次保持率：100%。

第一次实现使用冗长 Envelope 标题并保留过多历史，压缩比例为 1.598，出现 59.8% 膨胀。调整为规范化强约束、短标签、极短历史摘要和完整最近轮次后，才得到上述结果。失败结果保留为设计证据。

限制：这是字符级离线原型，不是 Qwen Tokenizer 统计，没有接入生产 ChatMemory，也不能声称 Token 或模型成本下降 34%。

### 自动化正确性

- `ContextCompressionExperimentTest`：通过；
- 固定 RAG 数据集：100 条，覆盖 destination、fuzzy_intent、multi_constraint、freshness、alias_colloquial、no_answer、adversarial；
- RAG 消融脚本与负载脚本已完成语法校验。

## 待环境执行项目

以下项目状态为 `NOT_MEASURED`，不能写入简历数字：

| 项目 | 状态 | 原因/下一步 |
|---|---|---|
| Agent Task API QPS/P95/P99 | NOT_MEASURED | 启动隔离环境后运行 `phase6_load.py --scenario api` |
| SSE 并发与首事件延迟 | NOT_MEASURED | 使用 Stub 模型运行 SSE 场景 |
| MQ 峰值积压与恢复速率 | NOT_MEASURED | Docker/K8s + RabbitMQ Management/Prometheus |
| Agent Worker 吞吐 | NOT_MEASURED | 固定模型时延与配额后端到端测量 |
| ES/PGVector/RRF 消融 | NOT_MEASURED | 三实例、同索引快照运行 `rag_ablation.py` |
| 故障注入 RPO/RTO | NOT_MEASURED | 执行 Phase 5 故障演练 Runbook |

## 复现入口

- 压测工具：[benchmark/README.md](../../benchmark/README.md)
- 上下文测试：`src/test/java/com/travelmind/aiagent/benchmark/ContextCompressionExperimentTest.java`
- RAG 数据集：`src/test/resources/evaluation/travel-rag-eval.jsonl`
- 上下文数据集：`src/test/resources/evaluation/context-compression-eval.jsonl`
