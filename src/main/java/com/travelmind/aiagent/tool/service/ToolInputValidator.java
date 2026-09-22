package com.travelmind.aiagent.tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Iterator;

@Component
public class ToolInputValidator {
    private final ObjectMapper objectMapper;

    public ToolInputValidator(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public JsonNode validate(String arguments, String inputSchema) {
        try {
            if (arguments == null || arguments.length() > 16_000) throw new IllegalArgumentException("工具参数为空或过长");
            JsonNode input = objectMapper.readTree(arguments);
            if (!input.isObject()) throw new IllegalArgumentException("工具参数必须是 JSON Object");
            JsonNode schema = objectMapper.readTree(inputSchema);
            JsonNode properties = schema.path("properties");
            for (JsonNode required : schema.path("required")) {
                JsonNode value = input.get(required.asText());
                if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank()))
                    throw new IllegalArgumentException("缺少必填参数: " + required.asText());
            }
            Iterator<String> names = input.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode value = input.get(name);
                JsonNode property = properties.path(name);
                if (schema.path("additionalProperties").isBoolean()
                        && !schema.path("additionalProperties").asBoolean() && property.isMissingNode())
                    throw new IllegalArgumentException("不允许的参数: " + name);
                validateType(name, value, property);
                if (value.isTextual() && value.asText().length() > 2000)
                    throw new IllegalArgumentException("参数过长: " + name);
                if (value.isTextual() && (name.toLowerCase().contains("url")
                        || "uri".equals(property.path("format").asText()))) validateUrl(value.asText());
            }
            return input;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数或 Schema 不是合法 JSON", e);
        }
    }

    private void validateType(String name, JsonNode value, JsonNode property) {
        String type = property.path("type").asText();
        boolean valid = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException("参数类型错误: " + name + " 应为 " + type);
        if (value.isTextual() && property.has("maxLength")
                && value.asText().length() > property.path("maxLength").asInt())
            throw new IllegalArgumentException("参数超过 Schema 长度限制: " + name);
        if (property.path("enum").isArray()) {
            boolean matched = false;
            for (JsonNode allowed : property.path("enum")) if (allowed.equals(value)) matched = true;
            if (!matched) throw new IllegalArgumentException("参数不在允许枚举中: " + name);
        }
    }

    private void validateUrl(String value) {
        URI uri = URI.create(value);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("仅允许 HTTP/HTTPS URL");
        String host = uri.getHost();
        String normalized = host == null ? "" : host.toLowerCase();
        if (host == null || normalized.equals("localhost") || normalized.equals("::1")
                || normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")
                || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("0.")
                || host.startsWith("192.168.") || host.startsWith("169.254.") || private172(host))
            throw new IllegalArgumentException("禁止访问本机或内网地址");
    }

    private boolean private172(String host) {
        if (!host.startsWith("172.")) return false;
        String[] parts = host.split("\\.");
        if (parts.length < 2) return false;
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
