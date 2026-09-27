# TravelMind 项目文档

本目录只保留能够对应当前代码、复盘实现并支持面试追问的主文档。历史 Phase 文档、重复架构图、旧 ReAct 描述和未验证的规划稿已经合并，不再作为独立事实来源。

## 阅读顺序

| 文档 | 解决的问题 |
|---|---|
| [01 系统架构与请求链路](01-SYSTEM-ARCHITECTURE.md) | 系统为什么这样拆、一次请求怎样穿过 API/MQ/Worker/存储 |
| [02 核心模块实现](02-CORE-MODULES.md) | Workflow、状态恢复、记忆上下文、RAG、Tool/MCP 如何实现 |
| [03 可观测、部署与运维](03-OBSERVABILITY-AND-OPERATIONS.md) | 指标、Trace、Run Explorer、发布、扩容、备份和故障演练 |
| [04 开发难点与解决方案](04-ENGINEERING-CHALLENGES.md) | 开发中真实遇到的问题、根因、修复和取舍 |
| [05 压测与评测](05-PERFORMANCE-AND-EVALUATION.md) | 压测方法、命令、真实数据、大小判断和证据边界 |
| [06 简历与面试手册](06-RESUME-AND-INTERVIEW.md) | 简历版本、项目介绍、证据矩阵和高频追问 |

## 事实边界

- 当前唯一 AI 入口是 `/api/agent/tasks`，旧 `/api/ai/**` 已删除。
- 当前编排是固定 StateGraph + 条件路由；节点内部按约束调用 RAG/Tool，不是开放式无限 ReAct。
- 已实现 Redis 有界短期对话、PlanningDraft、MySQL Checkpoint 和显式用户偏好；MySQL 全量聊天历史、滚动摘要和个人向量长期记忆尚未实现。
- Docker Compose 正式环境目前是单机中间件；Quorum Queue、Helm、HPA/PDB 等配置不等于完成了多节点高可用实测。
- 性能数字必须来自 [05 压测与评测](05-PERFORMANCE-AND-EVALUATION.md)。未经实测的可用率、RPO/RTO、零丢失和提升比例不得写入简历。
- 设计目标、已实现代码、自动化验证、真实环境验证是四个不同层级，面试时必须明确区分。

## 复现入口

- 单元与离线评测：`mvn test`
- 黑盒压测：`benchmark/load/phase6_load.py`
- RAG 消融：`benchmark/rag/rag_ablation.py`
- 可观测栈：`docker compose --profile observability up -d`
- Grafana：服务器本地 `127.0.0.1:3000`
- Prometheus：服务器本地 `127.0.0.1:9091`
- Agent Run Explorer：`/observability/{taskId}`
