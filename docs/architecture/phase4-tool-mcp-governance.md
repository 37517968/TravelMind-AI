# Phase 4：Tool/MCP 与高并发治理

## 调用链

```text
ChatClient / Harness
  -> GovernedToolCallback
  -> Tool Gateway
     -> 节点权限 + 风险等级 + JSON Schema / URL 校验
     -> Redis 当前缓存 / 过期兜底缓存
     -> Redisson 参数级互斥（跨实例防击穿）
     -> 有界隔离线程池（拒绝策略）
     -> Sentinel 用户 QPS + Tool 并发 + 异常比例熔断
     -> 连接超时 + 读取超时 + Future 总体超时
     -> 仅幂等调用有限重试
     -> 结果脱敏、裁剪、统一 ToolResult
     -> MySQL 审计
  -> 本地天气/地图/搜索，或远程 MCP
```

模型同步调用和整个 Reactor 流生命周期使用 `model.itinerary` Sentinel 资源保护。Harness 的天气、POI 和行程生成节点复用同一治理入口。

## 失败语义

| 错误码 | 含义 | 是否建议重试 |
|---|---|---|
| `TOOL_FORBIDDEN` | 工作流节点、角色或高风险授权不满足 | 否 |
| `TOOL_INVALID_ARGUMENT` | JSON、Schema 或 URL 安全校验失败 | 否 |
| `TOOL_TIMEOUT` | 第三方调用超过总体时限 | 是 |
| `TOOL_OVERLOADED` | 隔离线程池或同参数刷新繁忙 | 是 |
| `TOOL_RATE_LIMITED` | Sentinel 限流或熔断 | 是 |
| `TOOL_UPSTREAM_ERROR` | 第三方返回失败或未知异常 | 默认否 |

缓存分为短 TTL 的当前值和长 TTL 的 stale 值。实时调用失败时可返回 stale 数据并标记 `degraded=true`。Redis/Redisson 暂时不可用时跳过缓存互斥，由有界线程池和 Sentinel 继续保护主链路。

## MCP 上线约束

远程 MCP 默认关闭。生产启用时必须同时配置 HTTPS 地址、认证 Token、非空工具白名单、期望服务版本和 Schema 版本；任一约束缺失会在客户端初始化阶段拒绝连接。启动发现工具后，将输入 Schema 的 SHA-256 登记到 `mcp_tool_schema`；同一版本发生 Schema 漂移时拒绝注册，必须显式提升版本。

## 安全边界

- 生产 profile 无条件不注册通用 Shell/FileSystem Tool；抓取和下载工具默认关闭。
- 高风险工具还需要全局开关、`ADMIN` 角色和请求级 `highRiskApproved=true` 三重条件。
- URL 参数只允许 HTTP/HTTPS，并拒绝常见本机、IPv4 私网、链路本地和 IPv6 私网字面地址。
- 远程 MCP Tool 名增加服务名前缀，避免跨服务命名冲突。
- `prod` Profile 会在注册层和 Gateway 授权层强制关闭高风险 Tool，即使外部环境变量尝试覆盖开关也不会生效。

## 运维入口

`GET /admin/tools/stats` 返回最近 24 小时各工具调用量、成功率、缓存命中率、降级率、P50/P95 和估算成本。该接口目前复用项目现有管理端边界；正式暴露公网前应在网关或 Spring Security 中限制管理员访问。
