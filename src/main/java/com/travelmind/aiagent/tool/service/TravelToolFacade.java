package com.travelmind.aiagent.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.tool.model.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TravelToolFacade {
    private final Map<String, ToolCallback> callbacks;
    private final ObjectMapper objectMapper;

    public TravelToolFacade(@Qualifier("localGovernedTools") ToolCallback[] callbacks, ObjectMapper objectMapper) {
        this.callbacks = Arrays.stream(callbacks).collect(Collectors.toUnmodifiableMap(
                callback -> callback.getToolDefinition().name(), Function.identity()));
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> describeTools() {
        return callbacks.values().stream().map(callback -> {
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("name", callback.getToolDefinition().name());
            description.put("description", callback.getToolDefinition().description());
            description.put("inputSchema", callback.getToolDefinition().inputSchema());
            return description;
        }).toList();
    }

    public boolean hasTool(String toolName) {
        return callbacks.containsKey(toolName);
    }

    public String invoke(String toolName, Map<String, Object> arguments, String node,
                         String requestId, String userId) {
        return call(toolName, arguments == null ? Map.of() : arguments, node, requestId, userId);
    }

    /**
     * 工作流内部使用的类型化入口。外部工具即使失败也会被收敛为 ToolResult，避免节点解析任意字符串协议。
     */
    public ToolResult invokeTyped(String toolName, Map<String, Object> arguments, String node,
                                  String requestId, String userId) {
        String raw = invoke(toolName, arguments, node, requestId, userId);
        try {
            return objectMapper.readValue(raw, ToolResult.class);
        } catch (Exception error) {
            return new ToolResult(false, raw, toolName, null, null, "INVALID_TOOL_ENVELOPE",
                    false, true, false, List.of("工具返回值无法解析为治理协议"));
        }
    }

    private String call(String tool, Map<String, Object> arguments, String node, String requestId, String userId) {
        ToolCallback callback = callbacks.get(tool);
        if (callback == null) return "{\"success\":false,\"errorCode\":\"TOOL_NOT_REGISTERED\"}";
        try {
            ToolContext context = new ToolContext(Map.of(
                    "requestId", requestId == null ? "harness" : requestId,
                    "userId", userId == null ? "anonymous" : userId,
                    "workflowNode", node,
                    "roles", Set.of("USER")));
            return callback.call(objectMapper.writeValueAsString(arguments), context);
        } catch (Exception e) {
            return "{\"success\":false,\"errorCode\":\"TOOL_FACADE_ERROR\"}";
        }
    }
}
