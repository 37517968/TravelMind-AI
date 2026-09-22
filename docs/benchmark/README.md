# 基准测试说明

本目录保存可复现的性能、RAG 质量和 AI 成本基线。所有用于简历的量化数字都应能追溯到这里的测试条件、脚本和原始结果。

## 基线类型

### 1. 构建与正确性基线

- Java/Maven 版本；
- 编译是否成功；
- 单元测试通过/失败/跳过数量；
- Flyway 在真实 MySQL 上能否完成迁移和校验。

### 2. RAG 质量基线

- 固定评测集，不能为了让新方案得分更高而临时修改答案；
- 至少记录 Recall@K、MRR、空召回率和引用正确率；
- 分别测试纯 BM25、纯向量、RRF 混合检索；
- Phase 0 的 `RagQualityBaselineTest` 只覆盖当前 JVM 检索器和本地 hash embedding，是回归基线，不是生产质量证明。

### 3. 性能基线

必须分开测量：

- HTTP 任务提交延迟；
- RAG 检索延迟；
- 模型首 Token 延迟和完整响应时间；
- Tool 调用延迟；
- SSE 连接和事件推送；
- RabbitMQ 排队时间与 Worker 吞吐。

每次报告记录硬件、数据规模、并发、预热次数、持续时间、模型名称、网络环境和 P50/P95/P99。单次本地 `StopWatch` 结果不能当作正式性能结论。

### 4. Token 与成本基线

每个评测请求记录：

- system/skills Token；
- 最近对话 Token；
- 摘要和长期记忆 Token；
- RAG Token；
- Tool 结果 Token；
- 输出 Token；
- 模型、单价快照和估算成本。

真实 Token 优先读取模型响应 Usage；模型未返回 Usage 时才使用与模型匹配的 tokenizer 估算。不同模型 tokenizer 不同，禁止用“字符数除以固定值”作为正式成本数据。

## 执行方式

```powershell
$env:JAVA_HOME = 'D:\Java\jdk-21.0.8'
$mvn = '.\mvnw.cmd'
& $mvn '-Dmaven.repo.local=.m2\repository' test
```

Docker 可用时 `FlywayMigrationContainerTest` 会自动启动 MySQL 8.0.36；Docker 不可用时该测试显示为 skipped，不能记为通过。

Phase 6 黑盒压测、SSE、MQ、Worker 和 RAG 消融工具的参数与安全说明见 [benchmark/README.md](../../benchmark/README.md)。

当前报告：

- [Phase 6 离线评测报告](PHASE6-OFFLINE-2026-09-21.md)；
- [Phase 6 故障演练报告](PHASE6-FAILURE-DRILL-REPORT.md)。

## 报告规则

- 报告文件名：`BASELINE-YYYY-MM-DD.md`；
- 优化后使用同一数据集和环境复测；
- 同时保留失败结果和测试限制；
- 简历只引用正式报告中的结果。
