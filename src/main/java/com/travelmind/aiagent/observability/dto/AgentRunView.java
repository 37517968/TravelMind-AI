package com.travelmind.aiagent.observability.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 面向运行诊断的脱敏视图；不返回 Prompt、工具参数和 checkpoint 原始快照。 */
public record AgentRunView(
        TaskSummary task,
        List<ExecutionView> executions,
        List<NodeView> nodes,
        List<ToolCallView> tools,
        List<GraphEdge> graph,
        String grafanaUrl) {

    public record TaskSummary(Long id, String requestId, String taskType, String status, String workflowVersion,
                              String currentNode, Integer modelCalls, Integer tokens, Integer nodeExecutions,
                              LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt,
                              String errorCode, String errorMessage) { }

    public record ExecutionView(Long id, String commandType, String messageId, String traceId, String spanId,
                                String status, String workerInstance, LocalDateTime startedAt,
                                LocalDateTime finishedAt, Long durationMs, String errorType, String errorMessage) { }

    public record NodeView(Long checkpointId, Long executionId, String nodeId, String label, String nodeVersion,
                           Integer attempt, String status, String route, Long durationMs,
                           LocalDateTime startedAt, LocalDateTime finishedAt, boolean retryable,
                           String errorType, String errorMessage, List<String> warnings,
                           List<String> outputKeys) { }

    public record ToolCallView(Long id, String workflowNode, String toolName, String source, boolean success,
                               boolean cacheHit, boolean degraded, Integer attempts, Long durationMs,
                               String errorCode, BigDecimal estimatedCost, LocalDateTime createdAt) { }

    public record GraphEdge(String from, String to, String route) { }
}
