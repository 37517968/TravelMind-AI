package com.travelmind.aiagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将 checkpoint 原始 JSON 转成可在 Run Explorer 展示的安全快照。
 * 保留业务 Prompt 和旅行约束，但移除认证凭据，并限制深度、节点数和长文本。
 */
@Component
@RequiredArgsConstructor
public class CheckpointSnapshotSanitizer {
    private static final int MAX_DEPTH = 12;
    private static final int MAX_NODES = 2_000;
    private static final int MAX_ARRAY_ITEMS = 100;
    private static final int MAX_OBJECT_FIELDS = 150;
    private static final int MAX_TEXT_LENGTH = 4_000;
    private static final Set<String> EXACT_SENSITIVE_KEYS = Set.of(
            "authorization", "cookie", "setcookie", "password", "passwd",
            "conversationid", "apikey", "accesskey", "secretkey", "privatekey",
            "accesstoken", "refreshtoken", "securitycode");

    private final ObjectMapper objectMapper;

    public JsonNode sanitize(String json) {
        if (json == null || json.isBlank()) return NullNode.getInstance();
        try {
            return scrub(objectMapper.readTree(json), new Budget(MAX_NODES), 0);
        } catch (Exception invalidJson) {
            return TextNode.valueOf(limit(json));
        }
    }

    private JsonNode scrub(JsonNode value, Budget budget, int depth) {
        if (value == null || value.isNull()) return NullNode.getInstance();
        if (depth > MAX_DEPTH || !budget.consume()) return TextNode.valueOf("[TRUNCATED]");
        if (value.isTextual()) return TextNode.valueOf(limit(value.asText()));
        if (value.isObject()) return scrubObject(value, budget, depth);
        if (value.isArray()) return scrubArray(value, budget, depth);
        return value.deepCopy();
    }

    private JsonNode scrubObject(JsonNode value, Budget budget, int depth) {
        ObjectNode result = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        int count = 0;
        while (fields.hasNext() && count < MAX_OBJECT_FIELDS) {
            Map.Entry<String, JsonNode> field = fields.next();
            result.set(field.getKey(), sensitive(field.getKey())
                    ? TextNode.valueOf("[REDACTED]")
                    : scrub(field.getValue(), budget, depth + 1));
            count++;
        }
        if (fields.hasNext()) result.put("_truncated", "object contains additional fields");
        return result;
    }

    private JsonNode scrubArray(JsonNode value, Budget budget, int depth) {
        ArrayNode result = objectMapper.createArrayNode();
        int size = Math.min(value.size(), MAX_ARRAY_ITEMS);
        for (int index = 0; index < size; index++) result.add(scrub(value.get(index), budget, depth + 1));
        if (value.size() > MAX_ARRAY_ITEMS) result.add("[TRUNCATED " + (value.size() - MAX_ARRAY_ITEMS) + " ITEMS]");
        return result;
    }

    private boolean sensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return EXACT_SENSITIVE_KEYS.contains(normalized) || normalized.endsWith("password")
                || normalized.endsWith("apikey") || normalized.endsWith("accesstoken")
                || normalized.endsWith("refreshtoken") || normalized.endsWith("secretkey");
    }

    private String limit(String value) {
        if (value == null || value.length() <= MAX_TEXT_LENGTH) return value;
        return value.substring(0, MAX_TEXT_LENGTH) + "…[TRUNCATED]";
    }

    private static final class Budget {
        private int remaining;
        private Budget(int remaining) { this.remaining = remaining; }
        private boolean consume() { return remaining-- > 0; }
    }
}
