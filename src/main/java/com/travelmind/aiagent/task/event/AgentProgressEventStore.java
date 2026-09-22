package com.travelmind.aiagent.task.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgentProgressEventStore {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.task.stream-retention:1000}")
    private long retention;

    public void publish(Long taskId, String type, String nodeId, String status,
                        String message, int progress, Map<String, Object> details) {
        AgentProgressEvent event = new AgentProgressEvent(taskId, type, nodeId, status, message,
                progress, Instant.now(), details == null ? Map.of() : details);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("nodeId", nodeId == null ? "" : nodeId);
        body.put("status", status);
        body.put("progress", String.valueOf(progress));
        body.put("payload", json(event));
        try {
            redisTemplate.opsForStream().add(MapRecord.create(streamKey(taskId), body));
            redisTemplate.opsForStream().trim(streamKey(taskId), retention, true);
            redisTemplate.expire(streamKey(taskId), java.time.Duration.ofHours(24));
        } catch (RuntimeException ex) {
            // MySQL 是事实源；进度通道故障不能破坏任务执行。
            log.warn("Unable to append progress event for task {}: {}", taskId, ex.getMessage());
        }
    }

    public void publishToken(Long taskId, String nodeId, long sequence, String content) {
        if (content == null || content.isEmpty()) return;
        publish(taskId, "TOKEN", nodeId, "STREAMING", "行程生成中", 90,
                Map.of("sequence", sequence, "content", content));
    }

    public String streamKey(Long taskId) {
        return "agent:task:" + taskId + ":events";
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "{\"message\":\"event serialization failed\"}"; }
    }
}
