# 05 压测与评测

## 1. 为什么 Agent 不能只看一个 QPS

```text
客户端 -> API/鉴权 -> MySQL + Outbox -> RabbitMQ -> Worker
       -> Workflow -> LLM/RAG/Tool -> Redis Stream -> SSE
```

需要分别报告：

- **任务接入 req/s**：API、MySQL 和 Outbox 能否接住请求；
- **RAG query/s**：检索服务吞吐；
- **Agent task/s 或 task/min**：真正完成的旅行规划；
- **首事件/首 Token/终态**：用户感知延迟；
- **队列与资源**：接入速度是否长期超过消费速度；
- **答案质量**：性能提高是否牺牲约束、召回和引用正确性。

## 2. 指标体系

| 层级 | 指标 |
|---|---|
| API | QPS、错误率、P50/P95/P99、幂等命中 |
| Async | Outbox backlog、Rabbit ready/unacked、queue delay、积压清空时间 |
| Agent | SUCCEEDED/FAILED/WAITING/TIMEOUT、task/min、E2E P95 |
| SSE | 活跃连接、首事件、首 Token、流持续时间、重连重复/丢失 |
| Workflow | 节点 P95、失败/重试、路由分布、预算耗尽、恢复次数 |
| LLM | TTFT、总耗时、并发、输入/输出 Token、Token/s、限流和成本 |
| Tool/MCP | 按工具 P95、失败率、缓存命中、限流和降级 |
| RAG | P95、空召回、降级、Recall@K、MRR、NDCG、引用正确率 |
| Context | 上下文 Token、压缩率、硬约束保持率、会话泄漏数 |
| Infra | CPU、内存、GC、线程池、连接池、MySQL/Redis/ES/PG/Rabbit 饱和度 |
| Reliability | 重复执行、DLQ、恢复成功率、RTO/RPO |

## 3. 分层压测方法

必须按顺序执行，前一层不稳定时不放大下一层：

1. **Smoke**：1 并发、1～3 次，验证认证、会话、任务、SSE 和指标；
2. **Baseline**：1 并发固定问题，得到无排队模型/工具基线；
3. **Load**：1/2/4/8 阶梯，每一级观察吞吐是否增长、延迟是否恶化；
4. **Stress**：找到 SLO 首次失守点后停止；
5. **Spike**：验证 Outbox/Rabbit 突发积压与清空；
6. **Soak**：30 分钟到数小时验证内存、连接和事件增长；
7. **Failure**：受控停止 Worker/Rabbit/Redis 或模拟 Tool 超时。

正式环境使用固定任务上限、模型/地图成本上限和停止条件。本次测试在并发 2 出现明显排队后停止，没有继续并发 4。

## 4. 压测脚本

入口：`benchmark/load/phase6_load.py`，只依赖 Python 标准库。它会登录、为任务创建独立会话、输出原始样本与 Markdown/JSON，并可读取服务器本地 Prometheus。

服务器环境：

```bash
export AGENT_BASE_URL=http://localhost/api
export AGENT_USERNAME=loadtest
read -s AGENT_PASSWORD
export AGENT_PASSWORD
export PROMETHEUS_URL=http://localhost:9091
```

### 4.1 轻量状态查询

```bash
python3 benchmark/load/phase6_load.py \
  --scenario query --task-id 46 \
  --requests 200 --concurrency 8
```

### 4.2 幂等接入

所有请求复用同一 Idempotency-Key，只应创建一个真实任务：

```bash
python3 benchmark/load/phase6_load.py \
  --scenario idempotency \
  --requests 200 --concurrency 16 \
  --confirm-agent-tasks
```

### 4.3 RAG

```bash
python3 benchmark/load/phase6_load.py \
  --scenario rag --requests 200 --concurrency 4
```

### 4.4 真实 Agent 与 SSE

```bash
python3 benchmark/load/phase6_load.py \
  --scenario sse --requests 3 --concurrency 1 \
  --sse-timeout 300 --confirm-agent-tasks

python3 benchmark/load/phase6_load.py \
  --scenario sse --requests 4 --concurrency 2 \
  --sse-timeout 300 --confirm-agent-tasks
```

脚本同时记录提交、首事件、首 Token、完整流和终态。`api` 场景虽然只统计接入耗时，但仍会创建唯一任务并消耗模型，不适合直接在正式环境高并发运行。

### 4.5 RAG 消融

用相同数据、索引、TopK 和硬件分别启动 lexical、semantic、hybrid：

```bash
python3 benchmark/rag/rag_ablation.py \
  --mode lexical=http://localhost:8124/api \
  --mode semantic=http://localhost:8125/api \
  --mode hybrid=http://localhost:8126/api \
  --concurrency 8 --top-k 5
```

输出 Recall@K、MRR、无答案准确率、降级率和延迟。没有相同质量指标时，不能把更快的检索称为优化。

## 5. 2026-09-27 真实环境结果

目标：`http://101.34.30.32/api`。通过公网黑盒施压，新增真实 Agent 任务共 6 个；没有访问服务器内网 Prometheus，因此缺少资源和队列时序。

### 5.1 汇总

| 场景 | 请求模型 | 成功率 | 吞吐 | P50 | P95 | P99 |
|---|---|---:|---:|---:|---:|---:|
| 旧任务详情查询 | 20 / C2 | 95% | 0.669 req/s | 2133 ms | 3743 ms | 12359 ms |
| 幂等重复提交 | 50 / C4 / 同一 Key | 100% | 69.694 req/s | 50 ms | 114 ms | 114 ms |
| RAG 搜索 | 20 / C2 | 100% | 16.325 query/s | 42 ms | 462 ms | 486 ms |
| Agent + SSE | 2 tasks / C1 | 100% | 0.040 task/s | 完成 23.80 s | 完成 26.35 s | 完成 26.35 s |
| Agent + SSE | 3 tasks / C2 | 100% | 0.043 task/s | 完成 44.48 s | 完成 44.71 s | 完成 44.71 s |

### 5.2 Agent 用户体验

| 并发 | 首事件 P95 | 首 Token P95 | 完成 P95 | 完成吞吐 |
|---:|---:|---:|---:|---:|
| 1 | 0.65 s | 5.17 s | 26.35 s | 2.37 task/min |
| 2 | 23.97 s | 27.75 s | 44.71 s | 2.59 task/min |

并发 1 -> 2：吞吐提升约 9.3%，首 Token 约变为 5.37 倍，完成 P95 约变为 1.70 倍。吞吐收益远小于用户体验损失，因此没有继续并发 4。

### 5.3 数据大小判断

- **69.7 req/s 幂等提交**：单实例早期项目的接入热路径可用，但所有请求命中同一任务，不能说成 69.7 Agent/s。
- **16.3 query/s RAG**：固定问题集、并发 2 下表现尚可，但只有 20 样本且可能命中缓存，不能代表任意知识库规模。
- **0.04 task/s Agent**：作为 CRUD 很小；作为平均约 25 秒、单 Worker 的多节点 Agent，与 `1/25.08=0.0399 task/s` 理论值一致。适合演示和小流量，不足以承载大量同时规划。
- **0.669 req/s 旧详情查询**：明显偏小。根因是加载任务大字段和全部 Checkpoint，已增加轻量 `/status`，但优化后尚未正式复测。

建议在稳态测试前把新任务准入限制为约 `0.02 task/s`（1.2 task/min），允许短突发 2 个。该值是预留余量的工程建议，不是独立实测点。

## 6. 数据是否足够可信

这是一轮容量探测，不是 SLA 认证：

- RAG 只有 20 个请求；
- Agent C1 只有 2 个、C2 只有 3 个任务；
- 2～3 个样本的 P95 实际接近最慢样本；
- 没有固定服务器规格、模型和知识库快照；
- 没有 30～60 分钟稳态、故障注入和多轮重复；
- 缺少同轮 Prometheus CPU、队列、节点和外部供应商指标。

这些数据足以发现拐点和指导优化，不能承诺生产 SLA。面试必须说“本轮观测 P95”，不能把它包装成长周期分位数。

## 7. 自动化与离线评测

最近一次完整 Maven 测试：

```text
Tests run: 106, Failures: 0, Errors: 0, Skipped: 2
```

跳过项是 Docker/Testcontainers 与高德 live connectivity，不等于通过。

### 7.1 本地 RAG 回归

- 5 条本地回归样例；
- Recall@3 = 1.000；
- 最近一次本地平均耗时约 7.011 ms；
- 使用本地 hash embedding，只能做代码回归，不能代表生产语义质量。

仓库另有更大的评测集与消融脚本，正式生产 Recall/MRR/NDCG 仍需在 ES、PGVector、Embedding 和标注语料齐备时运行。

### 7.2 上下文压缩离线实验

- 10 个冻结样例；
- 原始字符 1500，压缩后 990；
- 字符比例 0.660，即减少约 34%；
- 硬约束和最近轮次保持率均为 100%。

首次实现曾膨胀到约 2397 字符，比例 1.598，修复后才得到上述结果。该实验没有使用生产模型 tokenizer，也没有接入线上模块，不能写成“Token/成本降低 34%”。

## 8. 初始门槛与停止条件

| 指标 | 探索阶段门槛 |
|---|---:|
| 场景成功率 | >= 99% |
| 任务提交 P95 | <= 1 s |
| SSE 首事件 P95 | <= 2 s |
| SSE 首 Token P95 | <= 30 s |
| Agent E2E P95 | <= 300 s |
| 队列 | 停止施压后持续下降并在约定时间清空 |
| 数据一致性 | 重复执行、跨用户/会话串话为 0 |

并发 2 的首事件已超出门槛，因此停止加压。外部模型/MCP 失败可以单独归因，但不能从总成功率中删除。

## 9. 如何形成正式面试数字

1. 记录 commit、CPU/内存、容器限额、模型、Worker 数、数据/索引规模；
2. 固定测试问题集并预热；
3. 每档 Agent 至少 20～100 个样本，运行多轮；
4. 同时保存压测 JSON、Prometheus 查询、Grafana 截图和 Trace ID；
5. 对轻量 `/status` 使用相同参数做优化前后 A/B；
6. RAG 同时报告性能与质量；
7. 运行 30～60 分钟稳态和受控故障场景；
8. 只把可复现报告中的数字写进简历。

报告至少包含环境、参数、成功率、P50/P95/P99、吞吐、错误分类、队列峰值/清空、资源峰值、Token/成本、质量指标和限制。

## 10. 当前仍未测量

- 轻量 `/status` 优化后的 QPS/P95；
- 最大 SSE 稳定连接数与真实断线重复/丢失率；
- Worker concurrency 1/2 和双副本的内部指标 A/B；
- 模型真实 Usage、Token/s 和账单成本；
- 混合 RAG 对纯 BM25/纯向量的生产质量提升；
- 多节点故障恢复成功率、RPO/RTO 和长期可用率。
