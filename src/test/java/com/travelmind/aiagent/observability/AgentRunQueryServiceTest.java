package com.travelmind.aiagent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.exception.BusinessException;
import com.travelmind.aiagent.observability.dto.AgentNodeDetailView;
import com.travelmind.aiagent.task.mapper.AgentTaskExecutionMapper;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.mapper.AgentWorkflowCheckpointMapper;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;
import com.travelmind.aiagent.tool.mapper.ToolAuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunQueryServiceTest {
    private final AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
    private final AgentTaskExecutionMapper executionMapper = mock(AgentTaskExecutionMapper.class);
    private final AgentWorkflowCheckpointMapper checkpointMapper = mock(AgentWorkflowCheckpointMapper.class);
    private final ToolAuditLogMapper toolMapper = mock(ToolAuditLogMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentRunQueryService service = new AgentRunQueryService(taskMapper, executionMapper,
            checkpointMapper, toolMapper, objectMapper, new CheckpointSnapshotSanitizer(objectMapper),
            "http://localhost:3000");

    private AgentTask task;
    private AgentWorkflowCheckpoint checkpoint;

    @BeforeEach
    void setUp() {
        task = new AgentTask();
        task.setId(35L);
        task.setUserId(7L);
        task.setConversationId("conversation-35");
        checkpoint = new AgentWorkflowCheckpoint();
        checkpoint.setId(101L);
        checkpoint.setTaskId(35L);
        checkpoint.setNodeId("CONSTRAINT_EXTRACTION");
        checkpoint.setNodeVersion("v1");
        checkpoint.setAttempt(1);
        checkpoint.setNodeStatus("SUCCEEDED");
        checkpoint.setStartedAt(LocalDateTime.now());
        checkpoint.setInputSnapshot("{\"prompt\":\"杭州三日游\",\"conversationId\":\"conversation-35\"}");
        checkpoint.setOutputSnapshot("{\"data\":{\"constraintSpec\":{\"destination\":\"杭州\"}}}");
        checkpoint.setStateSnapshot("{\"data\":{\"workflowRoute\":\"CONTINUE\"}}");
        when(taskMapper.selectById(35L)).thenReturn(task);
        when(checkpointMapper.selectById(101L)).thenReturn(checkpoint);
        when(executionMapper.selectByTaskId(35L)).thenReturn(List.of());
        when(toolMapper.selectByRequestId("35")).thenReturn(List.of());
    }

    @Test
    void shouldReturnSanitizedCheckpointForOwningConversation() {
        AgentNodeDetailView detail = service.getNodeForUser(35L, 101L, 7L);

        assertThat(detail.input().path("prompt").asText()).isEqualTo("杭州三日游");
        assertThat(detail.input().path("conversationId").asText()).isEqualTo("[REDACTED]");
        assertThat(detail.output().path("data").path("constraintSpec").path("destination").asText())
                .isEqualTo("杭州");
    }

    @Test
    void shouldRejectAnotherUserAndCrossTaskCheckpoint() {
        assertThatThrownBy(() -> service.getNodeForUser(35L, 101L, 8L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("当前用户");

        checkpoint.setTaskId(99L);
        assertThatThrownBy(() -> service.getNodeForUser(35L, 101L, 7L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("检查点不存在");
    }
}
