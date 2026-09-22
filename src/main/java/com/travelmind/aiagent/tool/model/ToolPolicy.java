package com.travelmind.aiagent.tool.model;

import java.time.Duration;
import java.util.Set;

public record ToolPolicy(String toolName, String source, ToolRiskLevel riskLevel,
                         boolean idempotent, boolean cacheable, Duration cacheTtl,
                         Duration timeout, int maxAttempts, int maxResultChars,
                         Set<String> allowedNodes, double estimatedCost) {
}
