package com.travelmind.aiagent.observability.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;

/** 单个工作流检查点的按需、脱敏详情。 */
public record AgentNodeDetailView(
        Long checkpointId,
        Long taskId,
        Long executionId,
        String traceId,
        String spanId,
        String nodeId,
        String label,
        String nodeVersion,
        Integer attempt,
        String status,
        Long durationMs,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorType,
        String errorMessage,
        JsonNode input,
        JsonNode output,
        JsonNode state,
        List<AgentRunView.ToolCallView> tools) { }
