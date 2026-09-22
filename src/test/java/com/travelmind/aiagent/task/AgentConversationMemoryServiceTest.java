package com.travelmind.aiagent.task;

import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.*;

class AgentConversationMemoryServiceTest {

    @Test
    void shouldExposeRoleAndContentAndAppendBothSidesOfConversation() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("conversation-1")).thenReturn(List.of(
                new UserMessage("我想去杭州"), new AssistantMessage("可以安排三日游")));
        AgentConversationMemoryService service = new AgentConversationMemoryService(chatMemory);

        assertThat(service.snapshot("conversation-1"))
                .extracting(item -> item.get("content"))
                .containsExactly("我想去杭州", "可以安排三日游");

        service.appendUser("conversation-1", "预算 3000 元");
        service.appendAssistant("conversation-1", "已调整预算");

        verify(chatMemory).add(eq("conversation-1"), isA(UserMessage.class));
        verify(chatMemory).add(eq("conversation-1"), isA(AssistantMessage.class));
    }

    @Test
    void redisMemoryFailureShouldNotBreakTaskSubmissionPath() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("conversation-1")).thenThrow(new IllegalStateException("redis unavailable"));
        doThrow(new IllegalStateException("redis unavailable"))
                .when(chatMemory).add(eq("conversation-1"), isA(UserMessage.class));
        AgentConversationMemoryService service = new AgentConversationMemoryService(chatMemory);

        assertThat(service.snapshot("conversation-1")).isEmpty();
        assertThatCode(() -> service.appendUser("conversation-1", "仍然允许任务进入 MySQL"))
                .doesNotThrowAnyException();
    }
}
