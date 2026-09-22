package com.travelmind.aiagent.tool.model;

import java.time.Instant;
import java.util.List;

public record ToolResult(boolean success, Object data, String source, Instant observedAt,
                         Instant expiresAt, String errorCode, boolean retryable,
                         boolean degraded, boolean cacheHit, List<String> warnings) {
}
