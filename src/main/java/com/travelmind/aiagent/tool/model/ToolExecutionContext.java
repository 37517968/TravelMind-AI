package com.travelmind.aiagent.tool.model;

import java.util.Set;

public record ToolExecutionContext(String requestId, String userId, String workflowNode,
                                   Set<String> roles, boolean highRiskApproved) {
    public static ToolExecutionContext anonymous() {
        return new ToolExecutionContext("anonymous", "anonymous", "CHAT", Set.of("USER"), false);
    }
}
