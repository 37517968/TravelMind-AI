# Phase 5：可观测性说明

## 数据链路

```text
Spring Boot / Spring AI / 业务 Observation
  ├─ Micrometer Metrics -> /actuator/prometheus -> Prometheus -> Grafana / Alertmanager
  └─ OpenTelemetry Trace -> OTel Collector -> Tempo -> Grafana

RabbitMQ Prometheus Plugin ─┐
Redis Exporter ─────────────┴-> Prometheus
```

本地启动监控栈：

```bash
docker compose --env-file .env --profile observability up -d
```

- Grafana：`http://localhost:3000`；
- Prometheus：`http://localhost:9091`；
- Alertmanager：`http://localhost:9093`；
- RabbitMQ 管理台：`http://localhost:15672`。
- 应用指标：各运行角色的 `:9090/actuator/prometheus`。

上述管理端口默认只绑定宿主机回环（compose 里的 `${ADMIN_BIND_IP:-127.0.0.1}`），服务器上建议用 SSH 隧道访问：

```bash
ssh -L 3000:127.0.0.1:3000 -L 9091:127.0.0.1:9091 -L 15672:127.0.0.1:15672 user@server
```

确需公网访问时在 `.env` 中设置 `ADMIN_BIND_IP=0.0.0.0`，并自行用防火墙或云安全组限制来源 IP。

Compose 中的 Tempo 使用单进程、本地块存储，只适合开发验证。生产应采用对象存储、鉴权/TLS、容量规划和按官方建议部署的高可用形态。

## 指标目录

| 领域 | 指标 | 说明 |
|---|---|---|
| HTTP | `http_server_requests_seconds_*` | QPS、错误率、P95/P99 |
| Task | `agent_task_submitted_total`、`agent_task_duration_seconds_*`、`agent_task_completed_total` | 幂等提交结果、任务耗时与结局 |
| Workflow | `agent_node_duration_seconds_*`、`agent_node_completed_total` | 节点耗时和结果 |
| Model | `agent_model_first_token_duration_seconds_*` | 流式生成首 Token 延迟 |
| Recovery | `agent_task_recovered_total`、`agent_outbox_backlog` | 租约恢复、Outbox 积压 |
| RAG | `rag_search_duration_seconds_*`、`rag_search_total` | 命中、空召回、缓存和降级 |
| Tool/MCP | `tool_calls_total`、`tool_duration_seconds_*`、`tool_estimated_cost_total` | 成功率、错误码、耗时与估算成本 |
| Knowledge | `knowledge_index_failures` | 待修复索引状态 |
| AI usage | `agent_model_tokens_24h`、`agent_model_calls_24h` | 最近 24 小时用量快照，不等同账单 |

数据库型 Gauge 每 15 秒读取一次事实表并缓存在进程内，Prometheus 抓取不会直接触发 SQL。多副本看板对这类 Gauge 使用 `max`，Counter/Timer 使用 `sum(rate(...))`。

## Trace 与隐私

- HTTP、RabbitMQ、Agent Task、Workflow Node、RAG 和 Tool Observation 共享 OpenTelemetry 上下文；
- `requestId` 会回写 `X-Request-Id`，并写入 MDC/Trace；`taskId`、`messageId`、`conversationId` 仅作为高基数 Trace 字段；
- Prometheus 标签只使用角色、状态、节点、Tool、有限错误码等有界维度，禁止加入 `userId`、`taskId` 和 Prompt；
- Spring AI 的 Prompt、Completion、检索正文 Observation 默认关闭，避免泄露用户内容；
- 生产默认 Trace 采样率为 10%，可通过 `OTEL_TRACES_SAMPLER_PROBABILITY` 调整。

## 看板与告警

预置看板分为性能、可靠性、质量、AI 用量/成本代理四组。告警覆盖 API 5xx、任务 P95/失败率、Outbox/RabbitMQ/DLQ、Tool 失败、RAG 降级、知识索引失败和 Redis 淘汰。Alertmanager 当前使用空接收器；上线前必须接入实际通知渠道，并将 runbook 链接替换为内部地址。

## 验证清单

1. 提交一次任务，在响应中记录 `X-Request-Id`；
2. Grafana/Tempo 按 Trace ID 检查 HTTP -> MQ -> Task -> Node -> RAG/Tool 链路；
3. Prometheus 执行 `up`，确认三个运行角色、RabbitMQ、Redis Exporter 全部为 1；
4. 人工制造一条受控 Tool 超时，确认 Trace error、`tool_calls_total` 和告警表达式一致；
5. 检查 Trace、日志和指标中不存在 Prompt、Completion、密钥和用户标识。

参考：

- [Grafana Tempo Docker 示例](https://grafana.com/docs/tempo/latest/docker-example/)
- [Tempo 部署与安全说明](https://grafana.com/docs/tempo/latest/set-up-for-tracing/setup-tempo/deploy/)
- [OpenTelemetry Collector 部署模式](https://opentelemetry.io/docs/collector/deploy/agent/)
