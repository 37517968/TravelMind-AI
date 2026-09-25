package com.travelmind.aiagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.harness.TravelPlanningGraphFactory;
import com.travelmind.aiagent.observability.dto.AgentRunView;
import com.travelmind.aiagent.task.mapper.AgentTaskExecutionMapper;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.mapper.AgentWorkflowCheckpointMapper;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.AgentTaskExecution;
import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;
import com.travelmind.aiagent.tool.mapper.ToolAuditLogMapper;
import com.travelmind.aiagent.tool.model.ToolAuditLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.travelmind.aiagent.harness.TravelPlanningGraphFactory.*;

@Service
public class AgentRunQueryService {
    private final AgentTaskMapper taskMapper;
    private final AgentTaskExecutionMapper executionMapper;
    private final AgentWorkflowCheckpointMapper checkpointMapper;
    private final ToolAuditLogMapper toolMapper;
    private final ObjectMapper objectMapper;
    private final String grafanaUrl;

    public AgentRunQueryService(AgentTaskMapper taskMapper, AgentTaskExecutionMapper executionMapper,
                                AgentWorkflowCheckpointMapper checkpointMapper, ToolAuditLogMapper toolMapper,
                                ObjectMapper objectMapper,
                                @Value("${observability.grafana.public-url:http://localhost:3000}") String grafanaUrl) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.checkpointMapper = checkpointMapper;
        this.toolMapper = toolMapper;
        this.objectMapper = objectMapper;
        this.grafanaUrl = grafanaUrl;
    }

    public AgentRunView get(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        List<AgentTaskExecution> executions = executionMapper.selectByTaskId(taskId);
        List<AgentWorkflowCheckpoint> checkpoints = checkpointMapper.selectByTaskId(taskId);
        List<ToolAuditLog> tools = toolMapper.selectByRequestId(String.valueOf(taskId));
        return new AgentRunView(task(task), executions.stream().map(this::execution).toList(),
                checkpoints.stream().map(value -> node(value, executions)).toList(),
                tools.stream().map(this::tool).toList(), graph(), grafanaUrl);
    }

    private AgentRunView.TaskSummary task(AgentTask value) {
        return new AgentRunView.TaskSummary(value.getId(), value.getRequestId(), value.getTaskType(), value.getStatus(),
                value.getWorkflowVersion(), value.getCurrentNode(), value.getModelCallsUsed(), value.getTokensUsed(),
                value.getNodeExecutionsUsed(), value.getCreatedAt(), value.getStartedAt(), value.getFinishedAt(),
                value.getErrorCode(), value.getErrorMessage());
    }

    private AgentRunView.ExecutionView execution(AgentTaskExecution value) {
        return new AgentRunView.ExecutionView(value.getId(), value.getCommandType(), value.getMessageId(),
                value.getTraceId(), value.getSpanId(), value.getStatus(), value.getWorkerInstance(),
                value.getStartedAt(), value.getFinishedAt(), value.getDurationMs(), value.getErrorType(),
                value.getErrorMessage());
    }

    private AgentRunView.NodeView node(AgentWorkflowCheckpoint value, List<AgentTaskExecution> executions) {
        ParsedOutput parsed = parseOutput(value.getOutputSnapshot());
        return new AgentRunView.NodeView(value.getId(), executionAt(value.getStartedAt(), executions),
                value.getNodeId(), TravelPlanningGraphFactory.label(value.getNodeId()), value.getNodeVersion(),
                value.getAttempt(), value.getNodeStatus(), parsed.route(), value.getDurationMs(), value.getStartedAt(),
                value.getFinishedAt(), Boolean.TRUE.equals(value.getRetryable()), value.getErrorType(),
                value.getErrorMessage(), parsed.warnings(), parsed.outputKeys());
    }

    private AgentRunView.ToolCallView tool(ToolAuditLog value) {
        return new AgentRunView.ToolCallView(value.getId(), value.getWorkflowNode(), value.getToolName(),
                value.getToolSource(), Boolean.TRUE.equals(value.getSuccess()), Boolean.TRUE.equals(value.getCacheHit()),
                Boolean.TRUE.equals(value.getDegraded()), value.getAttemptCount(), value.getDurationMs(),
                value.getErrorCode(), value.getEstimatedCost(), value.getCreatedAt());
    }

    private Long executionAt(LocalDateTime startedAt, List<AgentTaskExecution> executions) {
        if (startedAt == null) return null;
        AgentTaskExecution matched = null;
        for (AgentTaskExecution execution : executions) {
            if (execution.getStartedAt() != null && !execution.getStartedAt().isAfter(startedAt)) matched = execution;
        }
        return matched == null ? null : matched.getId();
    }

    private ParsedOutput parseOutput(String json) {
        if (json == null || json.isBlank()) return new ParsedOutput("", List.of(), List.of());
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            List<String> keys = new ArrayList<>();
            if (data.isObject()) data.fieldNames().forEachRemaining(keys::add);
            List<String> warnings = new ArrayList<>();
            if (root.path("warnings").isArray()) root.path("warnings").forEach(item -> warnings.add(limit(item.asText(), 240)));
            return new ParsedOutput(data.path("workflowRoute").asText(""), List.copyOf(warnings), List.copyOf(keys));
        } catch (Exception ignored) {
            return new ParsedOutput("", List.of("节点输出摘要无法解析"), List.of());
        }
    }

    private List<AgentRunView.GraphEdge> graph() {
        return List.of(
                edge(INTENT, EXTRACT, "CONTINUE"), edge(INTENT, BASE_PLAN, "MODIFY"), edge(INTENT, CHAT_REPLY, "CHAT"),
                edge(BASE_PLAN, EXTRACT, "CONTINUE"), edge(EXTRACT, CHECK, "NEXT"),
                edge(CHECK, CONTEXT, "CONTINUE"), edge(CONTEXT, CANDIDATES, "NEXT"),
                edge(CANDIDATES, ROUTE_SELECTION, "NEXT"), edge(ROUTE_SELECTION, DETAILS_CHECK, "CONTINUE"),
                edge(DETAILS_CHECK, SOLVE, "CONTINUE"), edge(SOLVE, MAP, "SAT"),
                edge(SOLVE, RELAX, "UNSAT/UNKNOWN"), edge(MAP, GENERATE, "NEXT"),
                edge(GENERATE, VALIDATE, "NEXT"), edge(VALIDATE, FRESHNESS, "VALID"),
                edge(VALIDATE, RELAX, "INVALID"), edge(FRESHNESS, PERSIST, "FRESH"),
                edge(FRESHNESS, RELAX, "STALE"));
    }

    private AgentRunView.GraphEdge edge(String from, String to, String route) {
        return new AgentRunView.GraphEdge(from, to, route);
    }

    private String limit(String value, int max) {
        return value == null ? "" : value.substring(0, Math.min(max, value.length()));
    }

    private record ParsedOutput(String route, List<String> warnings, List<String> outputKeys) { }
}
