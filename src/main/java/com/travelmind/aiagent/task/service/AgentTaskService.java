package com.travelmind.aiagent.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.travelmind.aiagent.task.dto.AgentTaskCreateRequest;
import com.travelmind.aiagent.task.dto.AgentTaskView;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.mapper.AgentWorkflowCheckpointMapper;
import com.travelmind.aiagent.task.mapper.OutboxEventMapper;
import com.travelmind.aiagent.task.messaging.AgentCommand;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.AgentTaskStatus;
import com.travelmind.aiagent.task.model.OutboxEvent;
import com.travelmind.aiagent.observability.PlatformObservability;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.*;

@Service
@RequiredArgsConstructor
public class AgentTaskService {
    private static final Set<String> IMMUTABLE_TASK_FIELDS = Set.of(
            "userId", "conversationId", "taskType", "maxModelCalls", "maxTokens",
            "maxNodeExecutions", "maxAgentSteps", "_supplementalVersion");
    private final AgentTaskMapper taskMapper;
    private final AgentWorkflowCheckpointMapper checkpointMapper;
    private final OutboxEventMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final PlatformObservability observability;
    private final AgentConversationMemoryService conversationMemory;

    @Transactional
    public AgentTask submit(String requestId, AgentTaskCreateRequest request) {
        AgentTask existing = taskMapper.selectByRequestId(requestId);
        if (existing != null) {
            observability.taskSubmitted("IDEMPOTENT_HIT");
            return existing;
        }
        try {
            AgentTask task = new AgentTask();
            task.setRequestId(requestId);
            task.setUserId(request.getUserId());
            task.setConversationId(request.getConversationId());
            task.setTaskType(request.getTaskType().name());
            task.setStatus(AgentTaskStatus.QUEUED.name());
            task.setWorkflowVersion("formal-travel-stategraph-v3");
            ObjectNode requestJson = objectMapper.valueToTree(request);
            requestJson.put("_supplementalVersion", 0);
            requestJson.set("conversationHistory",
                    objectMapper.valueToTree(conversationMemory.snapshot(request.getConversationId())));
            task.setRequestJson(writeJson(requestJson));
            task.setCancelRequested(false);
            task.setModelCallsUsed(0);
            task.setTokensUsed(0);
            task.setNodeExecutionsUsed(0);
            task.setVersion(0);
            taskMapper.insert(task);
            appendCommand(task, request.getTaskType().name().equals("MODIFY") ? PLAN_MODIFY : PLAN_CREATE);
            conversationMemory.appendUser(request.getConversationId(), request.getPrompt());
            observability.taskSubmitted("CREATED");
            return task;
        } catch (DuplicateKeyException race) {
            observability.taskSubmitted("IDEMPOTENT_RACE");
            return taskMapper.selectByRequestId(requestId);
        }
    }

    public AgentTaskView get(Long taskId) {
        AgentTask task = requireTask(taskId);
        return AgentTaskView.builder()
                .task(task)
                .checkpoints(checkpointMapper.selectByTaskId(taskId))
                .build();
    }

    @Transactional
    public AgentTask cancel(Long taskId) {
        requireTask(taskId);
        taskMapper.requestCancellation(taskId);
        return requireTask(taskId);
    }

    @Transactional
    public AgentTask pause(Long taskId) {
        requireTask(taskId);
        taskMapper.markPaused(taskId);
        return requireTask(taskId);
    }

    @Transactional
    public AgentTask resume(Long taskId, Map<String, Object> supplemental) {
        AgentTask task = requireTask(taskId);
        if (!AgentTaskStatus.WAITING_USER.name().equals(task.getStatus()) &&
                !AgentTaskStatus.FAILED.name().equals(task.getStatus())) {
            throw new IllegalStateException("只有 WAITING_USER 或 FAILED 任务可以恢复");
        }
        mergeSupplemental(task, supplemental);
        taskMapper.updateById(task);
        if (taskMapper.requeue(taskId) != 1) {
            throw new IllegalStateException("任务状态已变化，请刷新后重试");
        }
        appendCommand(task, TASK_RESUME);
        conversationMemory.appendUser(task.getConversationId(), Objects.toString(
                supplemental.getOrDefault("userClarification", supplemental), ""));
        return requireTask(taskId);
    }

    @Transactional
    public AgentTask retryNode(Long taskId, String nodeId) {
        AgentTask task = requireTask(taskId);
        var latest = checkpointMapper.selectLatest(taskId, nodeId);
        if (latest == null || !("FAILED".equals(latest.getNodeStatus()) || "WAITING_USER".equals(latest.getNodeStatus()))) {
            throw new IllegalArgumentException("节点没有可重试的执行记录: " + nodeId);
        }
        if (taskMapper.requeue(taskId) != 1) {
            throw new IllegalStateException("只有失败或等待用户的任务可以重试");
        }
        appendCommand(task, TASK_RESUME);
        return requireTask(taskId);
    }

    public AgentTask requireTask(Long id) {
        AgentTask task = taskMapper.selectById(id);
        if (task == null) throw new IllegalArgumentException("任务不存在: " + id);
        return task;
    }

    private void mergeSupplemental(AgentTask task, Map<String, Object> supplemental) {
        try {
            if (supplemental == null || supplemental.isEmpty()) {
                throw new IllegalArgumentException("恢复任务时必须提供补充信息");
            }
            Map<String, Object> effective = new LinkedHashMap<>(supplemental);
            Object accepted = supplemental.get("acceptedRelaxation");
            if (accepted instanceof Map<?, ?> changes) {
                changes.forEach((key, value) -> {
                    if (key != null) effective.put(key.toString(), value);
                });
                effective.remove("acceptedRelaxation");
            }
            Set<String> rejected = new java.util.LinkedHashSet<>();
            effective.keySet().stream()
                    .filter(key -> key == null || key.startsWith("_") || IMMUTABLE_TASK_FIELDS.contains(key))
                    .forEach(rejected::add);
            if (!rejected.isEmpty()) {
                throw new IllegalArgumentException("以下任务身份或执行预算字段不可在恢复时修改: " + rejected);
            }
            JsonNode parsed = objectMapper.readTree(task.getRequestJson());
            ObjectNode root = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();
            effective.forEach((key, value) -> root.set(key, objectMapper.valueToTree(value)));
            ArrayNode history = root.withArray("supplementalHistory");
            history.add(objectMapper.valueToTree(supplemental));
            root.put("_supplementalVersion", root.path("_supplementalVersion").asInt(0) + 1);
            task.setRequestJson(objectMapper.writeValueAsString(root));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("任务请求无法解析", e);
        }
    }

    private void appendCommand(AgentTask task, String routingKey) {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType("AGENT_TASK");
        event.setAggregateId(String.valueOf(task.getId()));
        event.setEventType("AGENT_TASK_COMMAND");
        event.setExchangeName(COMMAND_EXCHANGE);
        event.setRoutingKey(routingKey);
        event.setPayloadJson(writeJson(new AgentCommand(eventId, task.getId(), routingKey)));
        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());
        outboxMapper.insert(event);
    }

    @Transactional
    public boolean recoverStale(Long taskId, int staleSeconds) {
        if (taskMapper.recoverStaleRunning(taskId, staleSeconds) != 1) return false;
        appendCommand(requireTask(taskId), TASK_RESUME);
        return true;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
