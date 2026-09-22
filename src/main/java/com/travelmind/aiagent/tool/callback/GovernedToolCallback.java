package com.travelmind.aiagent.tool.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.tool.model.ToolExecutionContext;
import com.travelmind.aiagent.tool.model.ToolPolicy;
import com.travelmind.aiagent.tool.service.ToolGateway;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class GovernedToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final ToolPolicy policy;
    private final ToolGateway gateway;
    private final ObjectMapper objectMapper;

    public GovernedToolCallback(ToolCallback delegate, ToolPolicy policy, ToolGateway gateway, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.policy = policy;
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }

    @Override
    public String call(String toolInput) { return invoke(toolInput, ToolExecutionContext.anonymous()); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> values = toolContext == null ? Map.of() : toolContext.getContext();
        Set<String> roles = new HashSet<>();
        Object rawRoles = values.get("roles");
        if (rawRoles instanceof Iterable<?> iterable) iterable.forEach(role -> roles.add(role.toString()));
        if (roles.isEmpty()) roles.add("USER");
        ToolExecutionContext context = new ToolExecutionContext(
                Objects.toString(values.get("requestId"), UUID.randomUUID().toString()),
                Objects.toString(values.get("userId"), "anonymous"),
                Objects.toString(values.get("workflowNode"), "CHAT"), roles,
                Boolean.TRUE.equals(values.get("highRiskApproved")));
        return invoke(toolInput, context);
    }

    private String invoke(String input, ToolExecutionContext context) {
        try {
            return objectMapper.writeValueAsString(gateway.execute(policy,
                    delegate.getToolDefinition().inputSchema(), input, context, delegate::call));
        } catch (Exception serializationFailure) {
            return "{\"success\":false,\"errorCode\":\"TOOL_RESPONSE_SERIALIZATION_FAILED\"}";
        }
    }
}
