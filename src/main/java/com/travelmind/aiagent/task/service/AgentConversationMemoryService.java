package com.travelmind.aiagent.task.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将统一任务入口接入 Redis ChatMemory；记忆不可用时不阻断任务主链路。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentConversationMemoryService {
    private final ChatMemory chatMemory;

    public List<Map<String, String>> snapshot(String conversationId) {
        try {
            return chatMemory.get(conversationId).stream().map(this::view).toList();
        } catch (RuntimeException failure) {
            log.warn("Unable to load conversation memory {}: {}", conversationId, failure.getMessage());
            return List.of();
        }
    }

    public void appendUser(String conversationId, String content) {
        append(conversationId, new UserMessage(Objects.toString(content, "")));
    }

    public void appendAssistant(String conversationId, String content) {
        append(conversationId, new AssistantMessage(Objects.toString(content, "")));
    }

    public void clear(String conversationId) {
        try {
            chatMemory.clear(conversationId);
        } catch (RuntimeException failure) {
            log.warn("Unable to clear conversation memory {}: {}", conversationId, failure.getMessage());
        }
    }

    private void append(String conversationId, Message message) {
        if (message.getText() == null || message.getText().isBlank()) return;
        try {
            chatMemory.add(conversationId, message);
        } catch (RuntimeException failure) {
            log.warn("Unable to append conversation memory {}: {}", conversationId, failure.getMessage());
        }
    }

    private Map<String, String> view(Message message) {
        return Map.of("role", message.getMessageType().name(),
                "content", Objects.toString(message.getText(), ""));
    }
}
