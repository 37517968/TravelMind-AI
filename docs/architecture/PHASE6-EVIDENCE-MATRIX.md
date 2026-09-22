# 简历能力证据矩阵

| 简历能力 | 代码/配置证据 | 自动化或报告 | 当前状态 |
|---|---|---|---|
| 幂等任务 + Transactional Outbox | `AgentTaskService`、`OutboxPublisher`、Flyway V3 | `AgentTaskServiceTest` | 已实现、单测通过 |
| RabbitMQ 重试/DLQ/Quorum Queue | `AgentRabbitConfiguration`、`AgentTaskConsumer` | `AgentRabbitConfigurationTest` | 已实现；真实 Broker 故障待演练 |
| StateGraph 固定路由/Checkpoint | `TravelPlanningGraphFactory`、`ExplicitTravelWorkflowEngine`、`TravelWorkflowNodeCatalog` | `TravelPlanningGraphFactoryTest`、`ExplicitTravelWorkflowEngineTest` | 已实现；SAT/UNSAT/WAITING 路由单测通过 |
| 结构化约束与 SAT/UNSAT | `TravelConstraintSpec`、`Z3TravelConstraintSolver`、`DeterministicTravelConstraintSolver`、`solver-service` | `TravelConstraintExtractorTest`、`DeterministicTravelConstraintSolverTest` | 已实现；Z3 容器端到端待部署验证 |
| UNSAT 放宽与恢复闭环 | `UNSAT_RELAXATION` 节点、`AgentTaskService.acceptedRelaxation` 合并 | StateGraph UNSAT 路由测试 | 已实现；前端可继续使用 supplemental 自由输入 |
| 类型化 Tool/RAG 候选 | `TravelCandidateCollector`、`TravelToolFacade.invokeTyped`、`ToolGateway` | Tool/RAG 单元测试与正式工作流文档 | 已实现；上游 POI 价格不足时显式标记 ESTIMATED |
| 确定性校验与新鲜度 | `TravelPlanValidator`、`TravelFreshnessValidator` | 求解测试、TravelPlanner 风格回归集 | 已实现；真实库存二次确认仍受上游 API 能力限制 |
| 不可变执行预算与补充恢复 | `AgentTaskService`、`AgentTaskCreateRequest` | 服务测试待补充 | 已实现；恢复不重置用量且拒绝覆盖预算字段 |
| Redis Stream + SSE 续传 | `AgentProgressEventStore`、`AgentTaskEventController` | Phase 6 SSE 脚本 | 已实现；最大连接数待实测 |
| 混合检索/RRF | `KnowledgeHybridSearchService` | `KnowledgeHybridSearchServiceTest`、100 条数据集、消融脚本 | 已实现；生产消融数字待实测 |
| 知识闭环 | `KnowledgeIndexPipeline`、Knowledge Outbox/Consumer | `KnowledgeIndexPipelineTest` | 已实现、单测通过 |
| Tool/MCP 治理 | `ToolGateway`、Sentinel、Redisson、审计表 | `ToolGatewayTest`、MCP 测试 | 已实现、单测通过 |
| 指标/Trace/告警 | `PlatformObservability`、Prometheus/Grafana/Tempo 配置 | `PlatformObservabilityTest`、Phase 5 文档 | 已实现、静态配置通过 |
| 三角色部署/Helm | API/Agent Worker/Knowledge Worker Deployments | `helm lint/template`、发布 Runbook | Chart 已验证；真实滚动发布待执行 |
| 上下文压缩实验 | `ContextCompressionExperimentTest` | Phase 6 离线报告 | 字符实验通过；生产模块未接入 |
| TravelPlanner 风格约束评测 | `travel-planner-constraints.jsonl` | `TravelPlannerConstraintEvaluationTest` | 小型回归集已接入；非官方完整数据集 |
| 性能数字 | `phase6_load.py` | 待生成正式报告 | NOT_MEASURED |
| RPO/RTO | 故障演练 Runbook | Phase 6 故障报告 | NOT_EXECUTED |

面试时按“代码已实现、自动化已验证、真实环境待验证”三层回答，避免把设计目标说成线上结果。
