package com.travelmind.aiagent.harness;

import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;

import java.util.List;

public interface CheckpointStore {
    AgentWorkflowCheckpoint start(Long taskId, NodeExecutor node, int attempt, Object input, Object state);
    void succeed(AgentWorkflowCheckpoint checkpoint, Object output, Object state, long durationMs);
    void fail(AgentWorkflowCheckpoint checkpoint, Throwable error, boolean retryable, Object state, long durationMs);
    void waiting(AgentWorkflowCheckpoint checkpoint, Object output, Object state, long durationMs);
    AgentWorkflowCheckpoint latest(Long taskId, String nodeId);
    List<AgentWorkflowCheckpoint> list(Long taskId);
}
