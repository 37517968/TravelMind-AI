package com.travelmind.aiagent.harness;

import java.time.Duration;

public interface NodeExecutor {
    String nodeId();
    default String nodeVersion() { return "1.0"; }
    default Duration timeout() { return Duration.ofMinutes(2); }
    default int maxRetries() { return 1; }
    default boolean idempotent() { return true; }
    default boolean parallelizable() { return false; }
    NodeExecutionResult execute(WorkflowState state) throws Exception;
}
