# Agent 可观测与单次运行回放

## 能力分层

| 数据 | 存储 | 用途 |
|---|---|---|
| 聚合指标 | Micrometer -> Prometheus -> Grafana | 成功率、P95、积压、Token、节点和 Tool 趋势 |
| 技术调用链 | OpenTelemetry -> Collector -> Tempo | 定位一次执行在 HTTP、MQ、节点、RAG、模型和 Tool 中的耗时 |
| 业务运行事实 | MySQL execution/checkpoint/tool audit | 按 taskId 回放实际节点、路由、等待、恢复和重试 |

Prometheus 标签禁止写入 `taskId`、`conversationId`、用户标识和 Prompt。单次 Query 通过 MySQL 和 Tempo 查询。

## Query 与 Trace 的关联

`taskId` 是业务关联键。一次任务可能经历首次执行、`WAITING_USER`、恢复和重试，每次 RabbitMQ 消费写入一条 `agent_task_execution`：

- `command_type`：`PLAN_CREATE / PLAN_MODIFY / TASK_RESUME`；
- `trace_id / span_id`：本次执行段的 OpenTelemetry Trace；
- `status / duration_ms / worker_instance`：结果、耗时和 Worker；
- `error_type / error_message`：失败摘要。

任务提交事务把当前 W3C `traceparent` 保存到 `outbox_event.trace_parent`。Outbox Publisher 恢复该父上下文后创建 Producer Span，Spring AMQP 再将上下文注入消息。重试消息复制 `traceparent / tracestate`。用户稍后恢复任务时会产生新的 execution，通过相同 `taskId` 聚合。

## Run Explorer

管理员接口：

```http
GET /api/admin/agent/runs/{taskId}
```

需要登录的 `admin` 用户。接口返回脱敏任务摘要、execution/traceId、checkpoint 节点、路由和 Tool 调用；不会返回 Prompt、Completion、工具参数、密钥、`input_snapshot` 或完整 `state_snapshot`。

前端地址：

```text
/observability/{taskId}
```

新任务的 Agent 回复底部会出现“查看本次 Agent 编排路径”。旧任务没有 execution 记录，但仍能查看历史 checkpoint 与 Tool Audit。

## 新增指标

| 指标 | 说明 |
|---|---|
| `agent_task_queue_delay_seconds` | 命令等待 Worker 消费的延迟 |
| `agent_task_end_to_end_seconds` | 创建至最终状态的端到端耗时 |
| `agent_workflow_route_total` | 节点按 `CONTINUE / WAITING / SAT / UNSAT` 等路由计数 |
| `agent_model_calls_total` | 按节点统计模型调用量 |
| `agent_model_tokens_total` | 按节点统计 Token 估算量 |

`agent.tool.call` 是独立 Span，包含 Tool、来源、节点、成功、缓存和降级属性。

## 部署配置

`.env` 至少设置：

```dotenv
GRAFANA_ADMIN_PASSWORD=替换为强密码
OTEL_TRACES_SAMPLER_PROBABILITY=1.0
GRAFANA_PUBLIC_URL=https://你的Grafana域名
```

应用端采样设为 `1.0`，Collector 的 `tail_sampling` 最终保留：错误、超过 5 秒、异常任务结果的全部 Trace，以及 10% 正常 Trace。

更新并启动：

```bash
git pull --ff-only origin main
docker compose --env-file .env --profile observability up -d --build
```

Flyway 自动执行 `V7__add_agent_run_observability.sql`，不要手工建表。

管理端口默认只监听 `127.0.0.1`，远程访问建议建立隧道：

```bash
ssh -L 3000:127.0.0.1:3000 -L 9091:127.0.0.1:9091 user@server
```

Grafana 地址为 `http://localhost:3000`，预置看板 `Travel Agent Platform` 包含 Queue Delay、End-to-End、Workflow Routes 和 Model Tokens。

## Tempo 查询

在 Grafana Explore 选择 Tempo：

```traceql
{ span."agent.task.id" = "1001" }
```

查询失败节点：

```traceql
{ span:name = "agent.workflow.node" && span."agent.task.id" = "1001" && span:status = error }
```

正常短请求经过尾部采样后不保证全部进入 Tempo；MySQL Run Explorer 是业务路径回放的事实源。

## 上线检查

1. 用管理员账号登录；
2. 提交一个新任务并记录 taskId；
3. 点击回复下方的 Run Explorer；
4. 确认 execution 有 traceId、节点路径和工具调用；
5. 在 Grafana Tempo 搜索 traceId；
6. 在 Prometheus 检查新增指标；
7. 确认 API 和 Trace 中没有 Prompt、Completion、密钥和工具参数。
