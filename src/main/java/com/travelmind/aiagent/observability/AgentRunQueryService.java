package com.travelmind.aiagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.harness.TravelPlanningGraphFactory;
import com.travelmind.aiagent.common.ErrorCode;
import com.travelmind.aiagent.exception.BusinessException;
import com.travelmind.aiagent.observability.dto.AgentNodeDetailView;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private final CheckpointSnapshotSanitizer snapshotSanitizer;
    private final String grafanaUrl;

    public AgentRunQueryService(AgentTaskMapper taskMapper, AgentTaskExecutionMapper executionMapper,
                                AgentWorkflowCheckpointMapper checkpointMapper, ToolAuditLogMapper toolMapper,
                                ObjectMapper objectMapper, CheckpointSnapshotSanitizer snapshotSanitizer,
                                @Value("${observability.grafana.public-url:http://localhost:3000}") String grafanaUrl) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.checkpointMapper = checkpointMapper;
        this.toolMapper = toolMapper;
        this.objectMapper = objectMapper;
        this.snapshotSanitizer = snapshotSanitizer;
        this.grafanaUrl = grafanaUrl;
    }

    public AgentRunView get(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        return build(task);
    }

    public AgentRunView getForConversation(Long taskId, String conversationId) {
        AgentTask task = requireConversationTask(taskId, conversationId);
        return build(task);
    }

    public AgentNodeDetailView getNode(Long taskId, Long checkpointId) {
        return nodeDetail(requireTask(taskId), requireCheckpoint(taskId, checkpointId));
    }

    public AgentNodeDetailView getNodeForConversation(Long taskId, Long checkpointId, String conversationId) {
        return nodeDetail(requireConversationTask(taskId, conversationId), requireCheckpoint(taskId, checkpointId));
    }

    private AgentRunView build(AgentTask task) {
        Long taskId = task.getId();
        List<AgentTaskExecution> executions = executionMapper.selectByTaskId(taskId);
        List<AgentWorkflowCheckpoint> checkpoints = checkpointMapper.selectByTaskId(taskId);
        List<ToolAuditLog> tools = toolMapper.selectByRequestId(String.valueOf(taskId));
        return new AgentRunView(task(task), executions.stream().map(this::execution).toList(),
                checkpoints.stream().map(value -> node(value, executions)).toList(),
                tools.stream().map(this::tool).toList(), graph(), grafanaUrl);
    }

    private boolean same(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private AgentTask requireTask(Long taskId) {
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "任务不存在");
        return task;
    }

    private AgentTask requireConversationTask(Long taskId, String conversationId) {
        AgentTask task = requireTask(taskId);
        if (!same(task.getConversationId(), conversationId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能查看当前会话创建的任务链路");
        }
        return task;
    }

    private AgentWorkflowCheckpoint requireCheckpoint(Long taskId, Long checkpointId) {
        AgentWorkflowCheckpoint checkpoint = checkpointMapper.selectById(checkpointId);
        if (checkpoint == null || !taskId.equals(checkpoint.getTaskId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "节点检查点不存在");
        }
        return checkpoint;
    }

    private AgentNodeDetailView nodeDetail(AgentTask task, AgentWorkflowCheckpoint checkpoint) {
        List<AgentTaskExecution> executions = executionMapper.selectByTaskId(task.getId());
        AgentTaskExecution execution = executionFor(checkpoint.getStartedAt(), executions);
        List<AgentRunView.ToolCallView> tools = toolMapper.selectByRequestId(String.valueOf(task.getId())).stream()
                .filter(value -> related(checkpoint.getNodeId(), value.getWorkflowNode()))
                .map(this::tool).toList();
        return new AgentNodeDetailView(checkpoint.getId(), task.getId(),
                execution == null ? null : execution.getId(),
                execution == null ? null : execution.getTraceId(),
                execution == null ? null : execution.getSpanId(),
                checkpoint.getNodeId(), TravelPlanningGraphFactory.label(checkpoint.getNodeId()),
                checkpoint.getNodeVersion(), checkpoint.getAttempt(), checkpoint.getNodeStatus(),
                checkpoint.getDurationMs(), checkpoint.getStartedAt(), checkpoint.getFinishedAt(),
                checkpoint.getErrorType(), checkpoint.getErrorMessage(),
                snapshotSanitizer.sanitize(checkpoint.getInputSnapshot()),
                snapshotSanitizer.sanitize(checkpoint.getOutputSnapshot()),
                snapshotSanitizer.sanitize(checkpoint.getStateSnapshot()), tools);
    }

    private boolean related(String nodeId, String workflowNode) {
        if (nodeId == null || workflowNode == null) return false;
        String logicalNode = nodeId.split("_v")[0];
        return nodeId.startsWith(workflowNode) || workflowNode.startsWith(logicalNode);
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
        AgentTaskExecution matched = executionFor(startedAt, executions);
        return matched == null ? null : matched.getId();
    }

    private AgentTaskExecution executionFor(LocalDateTime startedAt, List<AgentTaskExecution> executions) {
        if (startedAt == null) return null;
        AgentTaskExecution matched = null;
        for (AgentTaskExecution execution : executions) {
            if (execution.getStartedAt() != null && !execution.getStartedAt().isAfter(startedAt)) matched = execution;
        }
        return matched;
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
