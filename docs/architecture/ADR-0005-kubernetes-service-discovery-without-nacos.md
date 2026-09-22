# ADR-0005：Kubernetes 环境不额外引入 Nacos

- 状态：Accepted
- 日期：2026-09-21

## 背景

平台以同一个镜像部署 Agent API、Agent Worker 和 Knowledge Worker。API 通过 MySQL + Transactional Outbox 提交任务，Worker 通过 RabbitMQ 解耦，组件运行在 Kubernetes 中。

## 决策

Phase 5 不引入 Nacos。同步入口使用 Kubernetes Service/DNS，异步链路通过 RabbitMQ；配置由 ConfigMap、Secret 和环境变量注入。

## 理由

- 当前没有基于 Spring Cloud 的服务间 RPC，也没有必须由 Nacos 提供的客户端负载均衡场景；
- Kubernetes 已提供服务发现、健康检查和滚动编排；
- 再引入一套注册中心会增加控制面、权限、备份、升级和故障排查成本；
- Agent API 与 Worker 通过持久化任务和消息队列解耦，不依赖注册中心发现消费者实例。

## 后果

- 本地环境继续使用 Docker Compose DNS；生产环境使用 Kubernetes Service；
- 动态配置不由 Nacos 承担，敏感值由外部 Secret 管理系统注入；
- 需要跨 Kubernetes 集群、接入大量非 K8s 服务或形成明确的动态配置治理需求时，再重新评估 Nacos/Consul 等方案。

## 替代方案

- Nacos：功能完整，但当前收益不足以覆盖新增运维复杂度；
- Spring Cloud Kubernetes：如果未来出现大量同步微服务调用，可单独评估；
- 自建注册表：拒绝，可靠性和治理成本不可接受。
