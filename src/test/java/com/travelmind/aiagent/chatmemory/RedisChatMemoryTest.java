package com.travelmind.aiagent.chatmemory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisChatMemoryTest {

    @Test
    void shouldStoreBoundedWindowAndRestoreToolCalls() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ListOperations<String, String> lists = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(lists);
        RedisChatMemory memory = new RedisChatMemory(redis, new ObjectMapper(), 20, Duration.ofDays(7));
        AssistantMessage assistant = new AssistantMessage("正在查询天气", Map.of("model", "qwen"),
                List.of(new AssistantMessage.ToolCall("call-1", "function", "getWeather", "{\"city\":\"杭州\"}")));

        memory.add("conversation-1", List.of(new UserMessage("杭州天气"), assistant));

        @SuppressWarnings("unchecked") ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), anyList(), arguments.capture());
        Object[] values = arguments.getValue();
        assertThat(values[0]).isEqualTo("20");
        assertThat(values[1]).isEqualTo(Long.toString(Duration.ofDays(7).toMillis()));
        when(lists.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of(values[2].toString(), values[3].toString()));

        List<Message> restored = memory.get("conversation-1");

        assertThat(restored).hasSize(2);
        assertThat(restored.getFirst()).isInstanceOf(UserMessage.class);
        AssistantMessage restoredAssistant = (AssistantMessage) restored.get(1);
        assertThat(restoredAssistant.getToolCalls()).singleElement()
                .satisfies(call -> assertThat(call.name()).isEqualTo("getWeather"));
    }
}
