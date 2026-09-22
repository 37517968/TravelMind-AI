package com.travelmind.aiagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.task.dto.AgentTaskCreateRequest;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.mapper.AgentWorkflowCheckpointMapper;
import com.travelmind.aiagent.task.mapper.OutboxEventMapper;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.OutboxEvent;
import com.travelmind.aiagent.task.service.AgentTaskService;
import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import com.travelmind.aiagent.observability.PlatformObservability;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentTaskServiceTest {
    @Test
    void submitShouldPersistTaskAndOutboxInOneServiceTransaction() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentWorkflowCheckpointMapper checkpointMapper = mock(AgentWorkflowCheckpointMapper.class);
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        AgentConversationMemoryService conversationMemory = mock(AgentConversationMemoryService.class);
        when(conversationMemory.snapshot("conversation-1")).thenReturn(List.of(
                Map.of("role", "USER", "content", "上一次讨论了亲子出行")));
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setId(88L);
            return 1;
        }).when(taskMapper).insert(any(AgentTask.class));
        AgentTaskService service = new AgentTaskService(taskMapper, checkpointMapper, outboxMapper,
                new ObjectMapper().findAndRegisterModules(), new PlatformObservability(), conversationMemory);
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setConversationId("conversation-1");
        request.setPrompt("上海三日游");
        request.setDestination("上海");
        request.setStartDate(LocalDate.of(2026, 10, 1));

        AgentTask task = service.submit("request-1", request);

        assertThat(task.getId()).isEqualTo(88L);
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getRequestJson()).contains("conversationHistory", "上一次讨论了亲子出行");
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxMapper).insert(event.capture());
        verify(conversationMemory).appendUser("conversation-1", "上海三日游");
        assertThat(event.getValue().getAggregateId()).isEqualTo("88");
        assertThat(event.getValue().getPayloadJson()).contains("taskId\":88");
    }

    @Test
    void repeatedIdempotencyKeyShouldReturnExistingTask() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask existing = new AgentTask();
        existing.setId(9L);
        when(taskMapper.selectByRequestId("same-key")).thenReturn(existing);
        AgentTaskService service = new AgentTaskService(taskMapper, mock(AgentWorkflowCheckpointMapper.class),
                mock(OutboxEventMapper.class), new ObjectMapper(), new PlatformObservability(),
                mock(AgentConversationMemoryService.class));

        AgentTask result = service.submit("same-key", new AgentTaskCreateRequest());

        assertThat(result).isSameAs(existing);
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void resumeShouldApplyAcceptedRelaxationAndIncrementSupplementalVersionWithoutResettingBudget() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        AgentConversationMemoryService memory = mock(AgentConversationMemoryService.class);
        AgentTask task = new AgentTask();
        task.setId(11L);
        task.setStatus("WAITING_USER");
        task.setConversationId("conversation-11");
        task.setModelCallsUsed(2);
        task.setTokensUsed(900);
        task.setRequestJson("{\"conversationId\":\"conversation-11\",\"budget\":3000," +
                "\"maxModelCalls\":8,\"_supplementalVersion\":0}");
        when(taskMapper.selectById(11L)).thenReturn(task);
        when(taskMapper.requeue(11L)).thenReturn(1);
        AgentTaskService service = new AgentTaskService(taskMapper, mock(AgentWorkflowCheckpointMapper.class),
                outboxMapper, new ObjectMapper(), new PlatformObservability(), memory);

        service.resume(11L, Map.of("acceptedRelaxation", Map.of("budget", 4500)));

        assertThat(task.getRequestJson()).contains("\"budget\":4500", "\"_supplementalVersion\":1");
        assertThat(task.getModelCallsUsed()).isEqualTo(2);
        assertThat(task.getTokensUsed()).isEqualTo(900);
        verify(outboxMapper).insert(any(OutboxEvent.class));
    }
}
