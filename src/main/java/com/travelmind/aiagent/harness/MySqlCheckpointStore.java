package com.travelmind.aiagent.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.task.mapper.AgentWorkflowCheckpointMapper;
import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;
import com.travelmind.aiagent.task.model.CheckpointStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MySqlCheckpointStore implements CheckpointStore {
    private final AgentWorkflowCheckpointMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public AgentWorkflowCheckpoint start(Long taskId, NodeExecutor node, int attempt, Object input, Object state) {
        AgentWorkflowCheckpoint checkpoint = new AgentWorkflowCheckpoint();
        checkpoint.setTaskId(taskId);
        checkpoint.setNodeId(node.nodeId());
        checkpoint.setNodeVersion(node.nodeVersion());
        checkpoint.setAttempt(attempt);
        checkpoint.setNodeStatus(CheckpointStatus.RUNNING.name());
        checkpoint.setInputSnapshot(json(input));
        checkpoint.setStateSnapshot(json(state));
        checkpoint.setStartedAt(LocalDateTime.now());
        checkpoint.setRetryable(false);
        mapper.insert(checkpoint);
        return checkpoint;
    }

    @Override
    public void succeed(AgentWorkflowCheckpoint checkpoint, Object output, Object state, long durationMs) {
        finish(checkpoint, CheckpointStatus.SUCCEEDED.name(), output, state, durationMs, null, false);
    }

    @Override
    public void fail(AgentWorkflowCheckpoint checkpoint, Throwable error, boolean retryable, Object state, long durationMs) {
        checkpoint.setErrorType(error.getClass().getSimpleName());
        checkpoint.setErrorMessage(truncate(error.getMessage()));
        finish(checkpoint, CheckpointStatus.FAILED.name(), null, state, durationMs, error, retryable);
    }

    @Override
    public void waiting(AgentWorkflowCheckpoint checkpoint, Object output, Object state, long durationMs) {
        finish(checkpoint, "WAITING_USER", output, state, durationMs, null, false);
    }

    private void finish(AgentWorkflowCheckpoint checkpoint, String status, Object output, Object state,
                        long durationMs, Throwable error, boolean retryable) {
        checkpoint.setNodeStatus(status);
        checkpoint.setOutputSnapshot(output == null ? null : json(output));
        checkpoint.setStateSnapshot(json(state));
        checkpoint.setFinishedAt(LocalDateTime.now());
        checkpoint.setDurationMs(durationMs);
        checkpoint.setRetryable(retryable);
        if (error != null) {
            checkpoint.setErrorType(error.getClass().getSimpleName());
            checkpoint.setErrorMessage(truncate(error.getMessage()));
        }
        mapper.updateById(checkpoint);
    }

    @Override public AgentWorkflowCheckpoint latest(Long taskId, String nodeId) { return mapper.selectLatest(taskId, nodeId); }
    @Override public List<AgentWorkflowCheckpoint> list(Long taskId) { return mapper.selectByTaskId(taskId); }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("检查点序列化失败", e);
        }
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
