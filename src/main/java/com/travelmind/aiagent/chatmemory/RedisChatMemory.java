package com.travelmind.aiagent.chatmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** 多副本共享的短期会话窗口；最终任务结果仍由 MySQL 保存。 */
@Component
@Slf4j
public class RedisChatMemory implements ChatMemory {
    private static final DefaultRedisScript<Long> APPEND_AND_TRIM = new DefaultRedisScript<>("""
            for i = 3, #ARGV do redis.call('RPUSH', KEYS[1], ARGV[i]) end
            redis.call('LTRIM', KEYS[1], -tonumber(ARGV[1]), -1)
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]))
            return redis.call('LLEN', KEYS[1])
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int maxMessages;
    private final Duration ttl;

    public RedisChatMemory(StringRedisTemplate redis, ObjectMapper objectMapper,
                           @Value("${agent.memory.redis.max-messages:50}") int maxMessages,
                           @Value("${agent.memory.redis.ttl:30d}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.maxMessages = Math.max(2, maxMessages);
        this.ttl = ttl;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        String key = key(conversationId);
        List<String> encoded = messages.stream().map(this::encode).filter(Objects::nonNull).toList();
        if (encoded.isEmpty()) return;
        List<String> args = new ArrayList<>(encoded.size() + 2);
        args.add(Integer.toString(maxMessages));
        args.add(Long.toString(Math.max(1, ttl.toMillis())));
        args.addAll(encoded);
        redis.execute(APPEND_AND_TRIM, List.of(key), args.toArray());
    }

    @Override
    public List<Message> get(String conversationId) {
        List<String> values = redis.opsForList().range(key(conversationId), 0, -1);
        if (values == null || values.isEmpty()) return List.of();
        List<Message> messages = new ArrayList<>();
        for (String value : values) {
            Message decoded = decode(value);
            if (decoded != null) messages.add(decoded);
        }
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        redis.delete(key(conversationId));
    }

    private String encode(Message message) {
        try {
            List<ToolCallSnapshot> calls = message instanceof AssistantMessage assistant
                    ? assistant.getToolCalls().stream().map(call -> new ToolCallSnapshot(
                    call.id(), call.type(), call.name(), call.arguments())).toList() : List.of();
            List<ToolResponseSnapshot> responses = message instanceof ToolResponseMessage tool
                    ? tool.getResponses().stream().map(response -> new ToolResponseSnapshot(
                    response.id(), response.name(), response.responseData())).toList() : List.of();
            return objectMapper.writeValueAsString(new MessageSnapshot(message.getMessageType().name(),
                    message.getText(), message.getMetadata(), calls, responses));
        } catch (Exception failure) {
            log.warn("Chat memory message serialization skipped: {}", failure.getMessage());
            return null;
        }
    }

    private Message decode(String json) {
        try {
            MessageSnapshot snapshot = objectMapper.readValue(json, MessageSnapshot.class);
            Map<String, Object> metadata = snapshot.metadata() == null ? Map.of() : snapshot.metadata();
            return switch (snapshot.type()) {
                case "USER" -> UserMessage.builder().text(snapshot.text()).metadata(metadata).build();
                case "ASSISTANT" -> new AssistantMessage(snapshot.text(), metadata,
                        snapshot.toolCalls().stream().map(call -> new AssistantMessage.ToolCall(
                                call.id(), call.type(), call.name(), call.arguments())).toList());
                case "SYSTEM" -> new SystemMessage(snapshot.text());
                case "TOOL" -> new ToolResponseMessage(snapshot.toolResponses().stream()
                        .map(response -> new ToolResponseMessage.ToolResponse(
                                response.id(), response.name(), response.responseData())).toList(), metadata);
                default -> null;
            };
        } catch (Exception failure) {
            log.warn("Corrupt chat memory entry skipped: {}", failure.getMessage());
            return null;
        }
    }

    private String key(String conversationId) {
        String safe = conversationId == null || conversationId.isBlank() ? DEFAULT_CONVERSATION_ID : conversationId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(safe.getBytes(StandardCharsets.UTF_8));
            return "agent:chat:memory:" + HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private record MessageSnapshot(String type, String text, Map<String, Object> metadata,
                                   List<ToolCallSnapshot> toolCalls,
                                   List<ToolResponseSnapshot> toolResponses) {
        private MessageSnapshot {
            toolCalls = toolCalls == null ? List.of() : toolCalls;
            toolResponses = toolResponses == null ? List.of() : toolResponses;
        }
    }
    private record ToolCallSnapshot(String id, String type, String name, String arguments) { }
    private record ToolResponseSnapshot(String id, String name, String responseData) { }
}
