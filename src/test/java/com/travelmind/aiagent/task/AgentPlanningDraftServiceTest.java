package com.travelmind.aiagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.task.service.AgentPlanningDraftService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentPlanningDraftServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistAndReloadStructuredPlanningSlots() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AgentPlanningDraftService service = new AgentPlanningDraftService(redis, mapper, Duration.ofDays(30));
        TravelConstraintSpec spec = new TravelConstraintSpec("", "上海", null, 3, 1, 200000L, "CNY",
                List.of(), List.of(), List.of(), null, Map.of(), Map.of(), 0);

        service.save("conversation-1", 12L, spec);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(startsWith("agent:planning:draft:"), json.capture(), eq(Duration.ofDays(30)));
        assertThat(json.getValue()).contains("\"destination\":\"上海\"", "\"status\":\"READY\"",
                "\"sourceTaskId\":12");

        when(values.get(anyString())).thenReturn(json.getValue());
        Map<String, Object> loaded = service.load("conversation-1");
        assertThat(loaded).containsEntry("status", "READY");
        assertThat((Map<String, Object>) loaded.get("constraintSpec")).containsEntry("destination", "上海");
    }

    @Test
    void redisFailureShouldDegradeToEmptyDraft() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));
        AgentPlanningDraftService service = new AgentPlanningDraftService(redis, new ObjectMapper(),
                Duration.ofDays(30));

        assertThat(service.load("conversation-2")).isEmpty();
        service.clear("conversation-2");

        verify(redis).delete(anyString());
    }
}
