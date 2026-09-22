package com.travelmind.aiagent.task.event;

import java.time.Instant;
import java.util.Map;

public record AgentProgressEvent(Long taskId, String type, String nodeId, String status,
                                 String message, int progress, Instant timestamp,
                                 Map<String, Object> details) {
}
