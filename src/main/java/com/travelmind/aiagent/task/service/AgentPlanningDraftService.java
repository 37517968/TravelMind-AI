package com.travelmind.aiagent.task.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话级旅行规划草稿。ChatMemory 保存自然语言，PlanningDraft 保存经过白名单约束抽取后的事实槽位。
 * Redis 不可用时退化为无草稿模式，不阻断异步任务主链路。
 */
@Service
@Slf4j
public class AgentPlanningDraftService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public AgentPlanningDraftService(StringRedisTemplate redis, ObjectMapper objectMapper,
                                     @Value("${agent.planning-draft.redis.ttl:30d}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Map<String, Object> load(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return Map.of();
        try {
            String json = redis.opsForValue().get(key(conversationId));
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception failure) {
            log.warn("Unable to load planning draft {}: {}", conversationId, failure.getMessage());
            return Map.of();
        }
    }

    public void save(String conversationId, Long taskId, TravelConstraintSpec spec) {
        if (conversationId == null || conversationId.isBlank() || spec == null) return;
        try {
            Map<String, Object> previous = load(conversationId);
            long previousTaskId = longValue(previous.get("sourceTaskId"), -1L);
            // 较早的并发任务不能覆盖同一会话中较新的规划草稿。
            if (taskId != null && previousTaskId > taskId) return;
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("conversationId", conversationId);
            draft.put("sourceTaskId", taskId);
            draft.put("version", intValue(previous.get("version"), 0) + 1);
            draft.put("constraintSpec", objectMapper.convertValue(spec, MAP_TYPE));
            draft.put("missingFields", spec.missingRequiredFields());
            draft.put("status", spec.complete() ? "READY" : "COLLECTING");
            draft.put("updatedAtEpochMs", System.currentTimeMillis());
            redis.opsForValue().set(key(conversationId), objectMapper.writeValueAsString(draft), ttl);
        } catch (Exception failure) {
            log.warn("Unable to save planning draft {}: {}", conversationId, failure.getMessage());
        }
    }

    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        try {
            redis.delete(key(conversationId));
        } catch (RuntimeException failure) {
            log.warn("Unable to clear planning draft {}: {}", conversationId, failure.getMessage());
        }
    }

    private String key(String conversationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(conversationId.getBytes(StandardCharsets.UTF_8));
            return "agent:planning:draft:" + HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return value == null ? fallback : Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
