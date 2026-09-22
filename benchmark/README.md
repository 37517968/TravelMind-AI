# Phase 6 压测与评测工具

所有脚本默认把原始 JSON 和 Markdown 报告写入 `target/phase6-reports/`。该目录是构建产物，不应提交；需要归档的报告经过人工核对后再复制到 `docs/benchmark/`。

## 安全前提

- Agent API、SSE、MQ、Worker 场景会创建真实任务，必须显式传入 `--confirm-agent-tasks`；
- 推荐使用隔离数据库、独立 RabbitMQ vhost、固定索引快照和 Stub/本地模型；
- 不要直接对生产环境压测；
- 每轮只改变一个变量，记录镜像版本、硬件、模型、数据规模、并发、预热和持续时间。

## 黑盒负载测试

脚本只依赖 Python 3 标准库：

```powershell
# 任务提交接口
python benchmark/load/phase6_load.py --scenario api --requests 500 --concurrency 32 --confirm-agent-tasks

# RAG 查询，不会创建 Agent 任务
python benchmark/load/phase6_load.py --scenario rag --requests 1000 --concurrency 32

# SSE 首事件和 Last-Event-ID 重连
python benchmark/load/phase6_load.py --scenario sse --requests 20 --concurrency 10 `
  --sse-reconnect --confirm-agent-tasks

# Outbox -> RabbitMQ 队列突发；读取 Management API 前后快照
$env:RABBITMQ_PASSWORD = '仅在当前终端设置'
python benchmark/load/phase6_load.py --scenario mq --requests 500 --concurrency 32 `
  --confirm-agent-tasks

# 提交到终态的 Agent Worker 端到端吞吐/耗时
python benchmark/load/phase6_load.py --scenario worker --requests 30 --concurrency 4 `
  --worker-timeout 600 --confirm-agent-tasks
```

`api` 场景只测任务接收延迟，但任务仍会异步消费模型额度。`worker` 场景测量端到端完成时间。`mq` 场景通过业务 API 和 Outbox 产生合法消息，不直接向队列塞入伪造 Payload。

## RAG 消融

准备三个使用同一数据库快照的实例：

| 实例 | 环境变量 |
|---|---|
| lexical | `TRAVEL_KNOWLEDGE_VECTORSTORE_TYPE=disabled` |
| semantic | `TRAVEL_KNOWLEDGE_ELASTICSEARCH_ENABLED=false`、`TRAVEL_KNOWLEDGE_VECTORSTORE_TYPE=pgvector` |
| hybrid | Elasticsearch 开启、`TRAVEL_KNOWLEDGE_VECTORSTORE_TYPE=pgvector` |

然后执行：

```powershell
python benchmark/rag/rag_ablation.py `
  --mode lexical=http://localhost:8124/api `
  --mode semantic=http://localhost:8125/api `
  --mode hybrid=http://localhost:8126/api `
  --concurrency 8 --top-k 5
```

输出包含 Recall@K、MRR、无答案准确率、降级率、P50/P95/P99 和逐样例结果。三种模式必须使用相同数据集、索引版本、候选数、TopK 和硬件。

## 离线上下文压缩实验

```powershell
$env:JAVA_HOME = 'D:\Java\jdk-21.0.8'
.\mvnw.cmd '-Dtest=ContextCompressionExperimentTest' test
```

该实验统计字符压缩比例、硬约束保持率和最近轮次保持率。它不是 Qwen Tokenizer 结果，也不是生产摘要质量证明。
