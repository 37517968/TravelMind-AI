package com.travelmind.aiagent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.rag.TravelKnowledgeIndexService;
import com.travelmind.aiagent.observability.PlatformObservability;
import com.travelmind.aiagent.governance.SentinelGovernanceService;
import com.travelmind.aiagent.task.event.AgentProgressEventStore;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;
import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExplicitTravelWorkflowEngineTest {
    @Test
    @SuppressWarnings("unchecked")
    void missingRequiredInputShouldCheckpointAndPauseInsteadOfCallingModel() throws Exception {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        CheckpointStore checkpointStore = mock(CheckpointStore.class);
        AgentProgressEventStore events = mock(AgentProgressEventStore.class);
        ChatModel chatModel = mock(ChatModel.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TravelWorkflowNodeCatalog catalog = new TravelWorkflowNodeCatalog(chatModel, mapper,
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(SentinelGovernanceService.class), events,
                new PlatformObservability());
        AgentTask task = new AgentTask();
        task.setId(7L);
        task.setStatus("RUNNING");
        task.setWorkflowVersion("travel-plan-v1");
        task.setModelCallsUsed(0);
        task.setTokensUsed(0);
        task.setNodeExecutionsUsed(0);
        task.setCancelRequested(false);
        task.setRequestJson(mapper.writeValueAsString(Map.of(
                "prompt", "", "maxModelCalls", 3, "maxTokens", 12000, "maxNodeExecutions", 32)));
        when(taskMapper.claimQueuedTask(7L)).thenReturn(1);
        when(taskMapper.selectById(7L)).thenReturn(task);
        when(checkpointStore.list(7L)).thenReturn(List.of());
        when(checkpointStore.latest(eq(7L), anyString())).thenReturn(null);
        when(checkpointStore.start(eq(7L), any(), anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentWorkflowCheckpoint cp = new AgentWorkflowCheckpoint();
                    cp.setTaskId(7L);
                    cp.setNodeId(((NodeExecutor) invocation.getArgument(1)).nodeId());
                    return cp;
                });

        ThreadPoolTaskExecutor parallelExecutor = new ThreadPoolTaskExecutor();
        parallelExecutor.setCorePoolSize(1);
        parallelExecutor.initialize();
        try (ExecutorService invocationExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            ExplicitTravelWorkflowEngine engine = new ExplicitTravelWorkflowEngine(taskMapper, checkpointStore,
                    catalog, events, mapper, parallelExecutor, invocationExecutor, new PlatformObservability(),
                    mock(AgentConversationMemoryService.class), new TravelPlanningGraphFactory());
            engine.execute(7L);
        } finally {
            parallelExecutor.shutdown();
        }

        verify(checkpointStore).waiting(any(), any(NodeExecutionResult.class), any(), anyLong());
        verify(taskMapper).markWaiting(eq(7L), eq(TravelPlanningGraphFactory.CHECK + "_v0"), contains("目的地"));
        verifyNoInteractions(chatModel);
    }
}
