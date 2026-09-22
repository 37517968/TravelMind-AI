package com.travelmind.aiagent.harness;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.task.event.AgentProgressEventStore;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.AgentTaskStatus;
import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;
import com.travelmind.aiagent.observability.PlatformObservability;
import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExplicitTravelWorkflowEngine implements WorkflowEngine {
    private final AgentTaskMapper taskMapper;
    private final CheckpointStore checkpointStore;
    private final TravelWorkflowNodeCatalog catalog;
    private final AgentProgressEventStore eventStore;
    private final ObjectMapper objectMapper;
    private final ThreadPoolTaskExecutor agentNodeExecutor;
    private final ExecutorService agentNodeInvocationExecutor;
    private final PlatformObservability observability;
    private final AgentConversationMemoryService conversationMemory;
    private final TravelPlanningGraphFactory graphFactory;

    @Override
    public void execute(Long taskId) {
        if (taskMapper.claimQueuedTask(taskId) != 1) {
            log.info("Task {} was already claimed or is no longer queued", taskId);
            return;
        }
        AgentTask task = taskMapper.selectById(taskId);
        WorkflowState state = new WorkflowState(taskId, readMap(task.getRequestJson()));
        eventStore.publish(taskId, "TASK", null, "RUNNING", "任务开始执行", 1, Map.of());
        try {
            restoreSuccessfulOutputs(taskId, state);
            boolean[] waiting = {false};
            CompiledGraph graph = graphFactory.compile(logicalNodeId -> {
                NodeExecutor node = catalog.fixedNode(logicalNodeId, state);
                NodeExecutionResult result = executeNode(task, state, node);
                if (pauseIfNeeded(taskId, node, result, state)) waiting[0] = true;
                String route = Objects.toString(result.getData().get("workflowRoute"), "CONTINUE");
                return Map.of("route", route, "lastNode", logicalNodeId);
            });
            Optional<OverAllState> graphState = graph.invoke(Map.of("taskId", taskId),
                    RunnableConfig.builder().threadId(String.valueOf(taskId)).build());
            if (waiting[0] || graphState.map(value -> "WAITING".equals(value.value("route", ""))).orElse(false)) return;

            Map<String, Object> resultPayload = new LinkedHashMap<>();
            resultPayload.put("itinerary", state.getData().getOrDefault("itinerary", ""));
            resultPayload.put("constraintSpec", state.getData().get("constraintSpec"));
            resultPayload.put("solverResult", state.getData().get("solverResult"));
            resultPayload.put("validationResult", state.getData().get("validationResult"));
            resultPayload.put("freshnessResult", state.getData().get("freshnessResult"));
            resultPayload.put("workflowVersion", task.getWorkflowVersion());
            resultPayload.put("metrics", state.getMetrics());
            String resultJson = objectMapper.writeValueAsString(resultPayload);
            taskMapper.markSucceeded(taskId, resultJson);
            conversationMemory.appendAssistant(task.getConversationId(),
                    Objects.toString(state.getData().get("itinerary"), ""));
            eventStore.publish(taskId, "TASK", null, "SUCCEEDED", "旅行方案生成完成", 100,
                    Map.of("resultAvailable", true));
        } catch (StopWorkflowException stopped) {
            log.info("Task {} stopped at a safe point", taskId);
        } catch (Exception raw) {
            Throwable cause = unwrap(raw);
            if (cause instanceof StopWorkflowException) {
                log.info("Task {} stopped at a safe point", taskId);
                return;
            }
            HarnessException failure = cause instanceof HarnessException h ? h :
                    new HarnessException("WORKFLOW_FAILED", Objects.toString(cause.getMessage(), "工作流执行失败"), true, cause);
            taskMapper.markFailed(taskId, failure.getErrorCode(), truncate(failure.getMessage()));
            eventStore.publish(taskId, "TASK", null, "FAILED", failure.getMessage(), 100,
                    Map.of("retryable", failure.isRetryable(), "errorCode", failure.getErrorCode()));
            throw failure;
        }
    }

    private NodeExecutionResult executeNode(AgentTask initialTask, WorkflowState state, NodeExecutor node) {
        Timer.Sample sample = observability.startTimer();
        Observation observation = observability.startNode(initialTask.getId(), node.nodeId());
        String outcome = "FAILED";
        try (Observation.Scope ignored = observation.openScope()) {
            NodeExecutionResult result = executeNodeInternal(initialTask, state, node);
            outcome = result.getStatus();
            return result;
        } catch (RuntimeException | Error failure) {
            observation.error(failure);
            throw failure;
        } finally {
            observation.lowCardinalityKeyValue("agent.node.outcome", outcome);
            observation.stop();
            observability.completeNode(sample, node.nodeId(), outcome);
        }
    }

    private NodeExecutionResult executeNodeInternal(AgentTask initialTask, WorkflowState state, NodeExecutor node) {
        AgentWorkflowCheckpoint previous = checkpointStore.latest(initialTask.getId(), node.nodeId());
        if (previous != null && "SUCCEEDED".equals(previous.getNodeStatus())) {
            return readResult(previous.getOutputSnapshot());
        }
        ensureTaskMayContinue(initialTask.getId(), state);
        enforceBudget(initialTask.getId(), state);
        int firstAttempt = previous == null ? 1 : previous.getAttempt() + 1;
        Throwable lastError = null;
        for (int offset = 0; offset <= node.maxRetries(); offset++) {
            int attempt = firstAttempt + offset;
            taskMapper.enterNode(initialTask.getId(), node.nodeId());
            AgentWorkflowCheckpoint checkpoint = checkpointStore.start(initialTask.getId(), node, attempt,
                    state.getRequest(), state.snapshot());
            long started = System.nanoTime();
            eventStore.publish(initialTask.getId(), "NODE", node.nodeId(), "RUNNING",
                    "执行节点 " + node.nodeId(), progress(node.nodeId()), Map.of("attempt", attempt));
            Future<NodeExecutionResult> invocation = null;
            try {
                invocation = agentNodeInvocationExecutor.submit(() -> node.execute(state));
                NodeExecutionResult result = invocation.get(node.timeout().toMillis(), TimeUnit.MILLISECONDS);
                long duration = elapsedMillis(started);
                if ("WAITING_USER".equals(result.getStatus())) {
                    checkpointStore.waiting(checkpoint, result, state.snapshot(), duration);
                    return result;
                }
                ensureResultWithinBudget(initialTask.getId(), state, result);
                state.merge(result.getData());
                state.getMetrics().put(node.nodeId() + ".durationMs", duration);
                taskMapper.addUsage(initialTask.getId(), result.getModelCalls(), result.getEstimatedTokens());
                checkpointStore.succeed(checkpoint, result, state.snapshot(), duration);
                eventStore.publish(initialTask.getId(), "NODE", node.nodeId(), "SUCCEEDED",
                        "节点完成 " + node.nodeId(), progress(node.nodeId()), Map.of("warnings", result.getWarnings()));
                return result;
            } catch (TimeoutException timeout) {
                if (invocation != null) invocation.cancel(true);
                lastError = new HarnessException("NODE_TIMEOUT", node.nodeId() + " 超过节点超时预算", true, timeout);
                checkpointStore.fail(checkpoint, lastError, true, state.snapshot(), elapsedMillis(started));
                if (offset >= node.maxRetries()) break;
            } catch (Throwable rawError) {
                Throwable error = unwrap(rawError);
                lastError = error;
                boolean retryable = !(error instanceof HarnessException h) || h.isRetryable();
                checkpointStore.fail(checkpoint, error, retryable, state.snapshot(), elapsedMillis(started));
                if (!retryable || offset >= node.maxRetries()) break;
                try { Thread.sleep(Math.min(1000, 200L << offset)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new HarnessException("NODE_INTERRUPTED", "节点重试被中断", true, interrupted);
                }
            }
        }
        if (lastError instanceof RuntimeException runtime) throw runtime;
        throw new HarnessException("NODE_FAILED", node.nodeId() + " 执行失败", true, lastError);
    }

    private boolean pauseIfNeeded(Long taskId, NodeExecutor node, NodeExecutionResult result, WorkflowState state) {
        if (!"WAITING_USER".equals(result.getStatus())) return false;
        String message = Objects.toString(result.getData().get("clarificationQuestion"),
                "缺少关键参数: " + result.getData().get("missingFields"));
        taskMapper.markWaiting(taskId, node.nodeId(), message);
        eventStore.publish(taskId, "TASK", node.nodeId(), "WAITING_USER", message,
                progress(node.nodeId()), result.getData());
        return true;
    }

    private void ensureTaskMayContinue(Long taskId, WorkflowState state) {
        AgentTask latest = taskMapper.selectById(taskId);
        if (Boolean.TRUE.equals(latest.getCancelRequested())) {
            taskMapper.markCancelled(taskId);
            eventStore.publish(taskId, "TASK", latest.getCurrentNode(), "CANCELLED", "任务已取消", 100, Map.of());
            throw new StopWorkflowException();
        }
        if (AgentTaskStatus.WAITING_USER.name().equals(latest.getStatus())) throw new StopWorkflowException();
    }

    private void enforceBudget(Long taskId, WorkflowState state) {
        AgentTask latest = taskMapper.selectById(taskId);
        int maxCalls = intValue(state.getRequest().get("maxModelCalls"), 8);
        int maxTokens = intValue(state.getRequest().get("maxTokens"), 12000);
        int maxNodes = intValue(state.getRequest().get("maxNodeExecutions"), 32);
        if (latest.getModelCallsUsed() >= maxCalls || latest.getTokensUsed() >= maxTokens ||
                latest.getNodeExecutionsUsed() >= maxNodes) {
            throw new HarnessException("BUDGET_EXHAUSTED", "工作流调用、Token 或节点执行预算已耗尽", false);
        }
    }

    private void ensureResultWithinBudget(Long taskId, WorkflowState state, NodeExecutionResult result) {
        AgentTask latest = taskMapper.selectById(taskId);
        int maxCalls = intValue(state.getRequest().get("maxModelCalls"), 8);
        int maxTokens = intValue(state.getRequest().get("maxTokens"), 12000);
        if (latest.getModelCallsUsed() + result.getModelCalls() > maxCalls ||
                latest.getTokensUsed() + result.getEstimatedTokens() > maxTokens) {
            throw new HarnessException("BUDGET_EXHAUSTED", "节点输出超过模型调用或 Token 预算", false);
        }
    }

    private void restoreSuccessfulOutputs(Long taskId, WorkflowState state) {
        for (AgentWorkflowCheckpoint cp : checkpointStore.list(taskId)) {
            if ("SUCCEEDED".equals(cp.getNodeStatus()) && cp.getOutputSnapshot() != null) {
                state.merge(readResult(cp.getOutputSnapshot()).getData());
            }
        }
    }

    private NodeExecutionResult readResult(String json) {
        try { return objectMapper.readValue(json, NodeExecutionResult.class); }
        catch (Exception e) { throw new HarnessException("CHECKPOINT_CORRUPTED", "检查点无法恢复", false, e); }
    }

    private Map<String, Object> readMap(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { throw new HarnessException("REQUEST_CORRUPTED", "任务请求无法恢复", false, e); }
    }

    private int progress(String nodeId) {
        if (nodeId.startsWith(TravelPlanningGraphFactory.EXTRACT)) return 8;
        if (nodeId.startsWith(TravelPlanningGraphFactory.CHECK)) return 14;
        if (nodeId.startsWith(TravelPlanningGraphFactory.CONTEXT)) return 25;
        if (nodeId.startsWith(TravelPlanningGraphFactory.CANDIDATES)) return 40;
        if (nodeId.startsWith(TravelPlanningGraphFactory.SOLVE)) return 58;
        if (nodeId.startsWith(TravelPlanningGraphFactory.RELAX)) return 65;
        if (nodeId.startsWith(TravelPlanningGraphFactory.GENERATE)) return 78;
        if (nodeId.startsWith(TravelPlanningGraphFactory.VALIDATE)) return 88;
        if (nodeId.startsWith(TravelPlanningGraphFactory.FRESHNESS)) return 94;
        if (nodeId.startsWith(TravelPlanningGraphFactory.PERSIST)) return 98;
        return 50;
    }

    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static Throwable unwrap(Throwable value) {
        Throwable current = value;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }
    private static int intValue(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private static final class StopWorkflowException extends RuntimeException {}
}
