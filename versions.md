# 依赖版本与兼容性基线

> 更新日期：2026-09-20。本文记录当前采用的版本和升级规则，不代表所有组件都已完成生产化部署。

## 当前核心版本

| 组件 | 当前版本/来源 | 状态 | 说明 |
|---|---|---|---|
| Java | 21 | 使用中 | 编译和运行统一使用 JDK 21 |
| Spring Boot | 3.4.4 | 使用中 | 父 POM 管理常规 Spring 依赖 |
| Spring AI | 1.0.0 BOM | 使用中 | ChatClient、RAG、PGVector、MCP 基线 |
| Spring AI Alibaba | 1.0.0.2 BOM | 使用中 | DashScope 接入 |
| DashScope SDK | 2.19.1 | 使用中 | 当前为显式版本 |
| Spring AI Agent Utils | 0.3.0 | 使用中 | Agent Skills；升级前必须验证 API |
| MyBatis-Plus | 3.5.5 | 使用中 | 与 Spring Boot 3 Starter 配套 |
| MySQL | 8.0 | 本地 Compose | 业务事实数据源 |
| Redis | 7-alpine | 本地 Compose | 当前缓存；Phase 1 扩展为分布式记忆 |
| RabbitMQ | 4.1-management-alpine | Phase 2 引入 | Agent 命令、延迟重试和 DLQ；关键队列使用 Quorum Queue |
| Spring AMQP | Spring Boot 3.4.4 BOM 管理 | Phase 2 引入 | Publisher Confirm、手动 ACK 和监听容器 |
| Elasticsearch | 8.15.5 | Phase 3 引入 | BM25、过滤、蓝绿索引和别名切换 |
| Spring Data Elasticsearch | 5.4.4（Boot BOM） | Phase 3 引入 | 对应 Elasticsearch Java Client 8.15.5 |
| PostgreSQL / PGVector | PostgreSQL 16 / `pgvector/pgvector:pg16` | Phase 3 引入 | HNSW + cosine 语义召回，独立数据源 |
| Flyway | Spring Boot BOM 管理 | Phase 0 引入 | `flyway-core` + `flyway-mysql` |
| Testcontainers | Spring Boot BOM 管理 | Phase 0 引入 | MySQL 集成测试；Docker 不可用时跳过 |
| Sentinel Core / Parameter Flow | 1.8.10 | Phase 4 引入 | Tool、模型并发限流，用户热点参数限流与异常比例熔断 |
| Redisson | 3.45.0 | Phase 4 引入 | 工具缓存防击穿和知识索引重建跨实例互斥 |
| MCP Java SDK | Spring AI 1.0.0 传递的 0.10.x | Phase 4 使用中 | 远程 SSE MCP、Bearer 认证、白名单与 Schema 版本登记 |

## 兼容性规则

1. Spring Boot、Spring AI、Spring AI Alibaba 使用各自 BOM，不为解决单一报错随意覆盖传递依赖版本。
2. Spring AI 跨大版本升级必须单独建迁移分支，重点验证 ChatMemory、Advisor 顺序、Tool Calling、MCP、VectorStore。
3. 同一个核心能力只保留一个主框架。LangChain4j 当前不是主编排框架；若无实际用途应在后续清理。
4. 数据库结构只能通过 `src/main/resources/db/migration` 中的 Flyway 脚本演进，已经发布的迁移不得修改。
5. Compose 和生产镜像必须固定可复现版本；`alpine` 等浮动标签在生产清单中需要替换为精确版本或 digest。
6. 升级前执行编译、单测、Testcontainers 集成测试和依赖树审计。

## 推荐验证命令

Windows 当前环境需要显式使用 JDK 21 和仓库内 Maven 缓存：

```powershell
$env:JAVA_HOME = 'D:\Java\jdk-21.0.8'
$mvn = 'D:\work\JAVATech\travelmind-ai\.m2\wrapper\dists\apache-maven-3.9.9\977a63e90f436cd6ade95b4c0e10c20c\bin\mvn.cmd'
& $mvn '-Dmaven.repo.local=D:\work\JAVATech\travelmind-ai\.m2\repository' test
& $mvn '-Dmaven.repo.local=D:\work\JAVATech\travelmind-ai\.m2\repository' dependency:tree
```

不要把以上机器绝对路径复制到 CI；CI 应直接使用 Maven Wrapper，并通过构建环境提供 JDK 21 和可写的 Maven Local Repository。
