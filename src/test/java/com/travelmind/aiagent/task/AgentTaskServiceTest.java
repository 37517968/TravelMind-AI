package com.travelmind.aiagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.task.dto.AgentTaskCreateRequest;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.mapper.AgentWorkflowCheckpointMapper;
import com.travelmind.aiagent.task.mapper.OutboxEventMapper;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.OutboxEvent;
import com.travelmind.aiagent.task.model.AgentTaskType;
import com.travelmind.aiagent.task.service.AgentTaskService;
import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import com.travelmind.aiagent.task.service.AgentPlanningDraftService;
import com.travelmind.aiagent.observability.PlatformObservability;
import com.travelmind.aiagent.observability.TraceContextCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentTaskServiceTest {
    @Test
    void submitShouldPersistTaskAndOutboxInOneServiceTransaction() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentWorkflowCheckpointMapper checkpointMapper = mock(AgentWorkflowCheckpointMapper.class);
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        AgentConversationMemoryService conversationMemory = mock(AgentConversationMemoryService.class);
        AgentPlanningDraftService planningDraftService = mock(AgentPlanningDraftService.class);
        when(conversationMemory.snapshot("conversation-1")).thenReturn(List.of(
                Map.of("role", "USER", "content", "上一次讨论了亲子出行")));
        when(planningDraftService.load("conversation-1")).thenReturn(Map.of(
                "constraintSpec", Map.of("destination", "上海", "maxBudgetCents", 200000L),
                "status", "READY"));
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setId(88L);
            return 1;
        }).when(taskMapper).insert(any(AgentTask.class));
        AgentTaskService service = new AgentTaskService(taskMapper, checkpointMapper, outboxMapper,
                new ObjectMapper().findAndRegisterModules(), new PlatformObservability(), mock(TraceContextCodec.class), conversationMemory,
                planningDraftService);
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setConversationId("conversation-1");
        request.setPrompt("上海三日游");
        request.setDestination("上海");
        request.setStartDate(LocalDate.of(2026, 10, 1));

        AgentTask task = service.submit("request-1", request);

        assertThat(task.getId()).isEqualTo(88L);
        assertThat(task.getStatus()).isEqualTo("QUEUED");
        assertThat(task.getRequestJson()).contains("conversationHistory", "上一次讨论了亲子出行",
                "planningDraft", "上海");
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
                mock(TraceContextCodec.class), mock(AgentConversationMemoryService.class), mock(AgentPlanningDraftService.class));

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
                outboxMapper, new ObjectMapper(), new PlatformObservability(), mock(TraceContextCodec.class), memory,
                mock(AgentPlanningDraftService.class));

        service.resume(11L, Map.of("acceptedRelaxation", Map.of("budget", 4500)));

        assertThat(task.getRequestJson()).contains("\"budget\":4500", "\"_supplementalVersion\":1");
        assertThat(task.getModelCallsUsed()).isEqualTo(2);
        assertThat(task.getTokensUsed()).isEqualTo(900);
        verify(outboxMapper).insert(any(OutboxEvent.class));
    }

    @Test
    void modifyTaskShouldSnapshotOwnedSucceededBasePlan() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask base = new AgentTask();
        base.setId(41L);
        base.setUserId(7L);
        base.setConversationId("conversation-41");
        base.setTaskType("PLAN");
        base.setStatus("SUCCEEDED");
        base.setResultJson("{\"responseType\":\"PLAN\",\"itinerary\":\"旧行程\"," +
                "\"constraintSpec\":{\"destination\":\"北京\"}}");
        when(taskMapper.selectById(41L)).thenReturn(base);
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setId(42L);
            return 1;
        }).when(taskMapper).insert(any(AgentTask.class));
        AgentTaskService service = new AgentTaskService(taskMapper, mock(AgentWorkflowCheckpointMapper.class),
                mock(OutboxEventMapper.class), new ObjectMapper(), new PlatformObservability(),
                mock(TraceContextCodec.class), mock(AgentConversationMemoryService.class), mock(AgentPlanningDraftService.class));
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setUserId(7L);
        request.setConversationId("conversation-41");
        request.setTaskType(AgentTaskType.MODIFY);
        request.setBaseTaskId(41L);
        request.setPrompt("把第二天的故宫改成长城");

        AgentTask created = service.submit("modify-41", request);

        assertThat(created.getRequestJson()).contains("\"baseTaskId\":41", "basePlanSnapshot", "旧行程");
        verify(taskMapper).selectById(41L);
    }

    @Test
    void chatResultMustNotBeAcceptedAsBasePlan() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentTask chat = new AgentTask();
        chat.setId(51L);
        chat.setConversationId("conversation-51");
        chat.setTaskType("PLAN");
        chat.setStatus("SUCCEEDED");
        chat.setResultJson("{\"responseType\":\"CHAT\",\"itinerary\":\"请告诉我目的地\"}");
        when(taskMapper.selectById(51L)).thenReturn(chat);
        AgentTaskService service = new AgentTaskService(taskMapper, mock(AgentWorkflowCheckpointMapper.class),
                mock(OutboxEventMapper.class), new ObjectMapper(), new PlatformObservability(),
                mock(TraceContextCodec.class), mock(AgentConversationMemoryService.class), mock(AgentPlanningDraftService.class));
        AgentTaskCreateRequest request = new AgentTaskCreateRequest();
        request.setConversationId("conversation-51");
        request.setTaskType(AgentTaskType.MODIFY);
        request.setBaseTaskId(51L);
        request.setPrompt("修改上一版行程");

        assertThatThrownBy(() -> service.submit("modify-chat", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("基线计划不存在");
    }

    @Test
    void workflowVersionMustFitDatabaseColumnWidth() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*.sql");
        assertThat(migrations).as("db/migration 下的迁移脚本").isNotEmpty();
        int declaredWidth = 0;
        for (Resource migration : Arrays.stream(migrations)
                .sorted(Comparator.comparing(resource -> String.valueOf(resource.getFilename())))
                .toList()) {
            String sql = new String(migration.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("workflow_version`?\\s*VARCHAR\\s*\\((\\d+)\\)", Pattern.CASE_INSENSITIVE)
                    .matcher(sql);
            while (matcher.find()) {
                declaredWidth = Integer.parseInt(matcher.group(1));
            }
        }
        assertThat(declaredWidth).as("迁移脚本中 agent_task.workflow_version 的列宽").isGreaterThan(0);
        assertThat(AgentTaskService.WORKFLOW_VERSION).as("工作流版本必须能写入 workflow_version 列")
                .hasSizeLessThanOrEqualTo(declaredWidth);
    }
}
