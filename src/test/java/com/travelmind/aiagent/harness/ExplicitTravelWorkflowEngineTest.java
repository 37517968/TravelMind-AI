package com.travelmind.aiagent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.governance.SentinelGovernanceService;
import com.travelmind.aiagent.observability.PlatformObservability;
import com.travelmind.aiagent.task.event.AgentProgressEventStore;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;
import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExplicitTravelWorkflowEngineTest {
    @Test
    @SuppressWarnings("unchecked")
    void missingRequiredInputShouldCheckpointAndPauseInsteadOfCallingModel() throws Exception {
        Fixture fixture = fixture(7L, Map.of("prompt", "", "maxModelCalls", 3, "maxTokens", 12000,
                "maxNodeExecutions", 32), List.of());
        try (fixture) {
            fixture.engine().execute(7L);
        }

        assertThat(fixture.startedNodes()).containsExactly(TravelPlanningGraphFactory.INTENT + "_v0",
                TravelPlanningGraphFactory.EXTRACT + "_v0", TravelPlanningGraphFactory.CHECK + "_v0");
        verify(fixture.checkpoints()).waiting(any(), any(NodeExecutionResult.class), any(), anyLong());
        verify(fixture.tasks()).markWaiting(eq(7L), eq(TravelPlanningGraphFactory.CHECK + "_v0"), contains("目的地"));
        verifyNoInteractions(fixture.chatModel());
    }

    @Test
    void greetingShouldBeRoutedToChatReplyWithoutPlanning() throws Exception {
        Fixture fixture = fixture(9L, Map.of("prompt", "你好", "maxModelCalls", 3, "maxTokens", 12000,
                "maxNodeExecutions", 32), List.of());
        try (fixture) {
            fixture.engine().execute(9L);
        }

        assertThat(fixture.startedNodes()).containsExactly(TravelPlanningGraphFactory.INTENT + "_v0",
                TravelPlanningGraphFactory.CHAT_REPLY + "_v0");
        verify(fixture.tasks()).markSucceeded(eq(9L), argThat(json -> json.contains("\"responseType\":\"CHAT\"")
                && json.contains("TravelMind")));
        verify(fixture.tasks(), never()).markWaiting(anyLong(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayedCheckpointsShouldRebuildStateAfterANewPlanRestart() throws Exception {
        List<AgentWorkflowCheckpoint> previous = List.of(
                successCheckpoint(13L, TravelPlanningGraphFactory.INTENT + "_v1", fixtureMapper,
                        NodeExecutionResult.builder().data(Map.of("intent", "NEW_PLAN", "newPlan", true,
                                "workflowRoute", "CONTINUE")).build()),
                successCheckpoint(13L, TravelPlanningGraphFactory.EXTRACT + "_v1", fixtureMapper,
                        NodeExecutionResult.builder().data(Map.of("constraintSpec",
                                Map.of("destination", "三亚", "days", 3, "maxBudgetCents", 500_000L))).build()),
                successCheckpoint(13L, TravelPlanningGraphFactory.CHECK + "_v1", fixtureMapper,
                        NodeExecutionResult.builder().data(Map.of("constraintsValidated", true,
                                "workflowRoute", "CONTINUE")).build()));
        Fixture fixture = fixture(13L, Map.of(
                "prompt", "帮我规划三亚", "_supplementalVersion", 1,
                "maxModelCalls", 6, "maxTokens", 12000, "maxNodeExecutions", 32), previous);
        String errorCode = null;
        try (fixture) {
            fixture.engine().execute(13L);
        } catch (HarnessException failure) {
            errorCode = failure.getErrorCode();
        }

        // 桩模型不可用只应影响 ITINERARY_GENERATION，不应表现为工作流状态丢失。
        assertThat(errorCode).isEqualTo("MODEL_CALL_FAILED");
        assertThat(fixture.startedNodes()).contains(TravelPlanningGraphFactory.CONTEXT + "_v1");
        Map<String, Object> stateAtContext = (Map<String, Object>) fixture.stateSnapshots()
                .get(TravelPlanningGraphFactory.CONTEXT + "_v1");
        assertThat((Map<String, Object>) stateAtContext.get("data")).containsKey("constraintSpec");
    }

    @Test
    @SuppressWarnings("unchecked")
    void newPlanIntentShouldDiscardPreviousTripStateBeforeExtraction() throws Exception {
        NodeExecutionResult previous = NodeExecutionResult.builder()
                .data(Map.of("constraintSpec", Map.of("destination", "杭州"), "itinerary", "旧杭州行程"))
                .build();
        Fixture fixture = fixture(11L, Map.of(
                "prompt", "帮我规划杭州三日游",
                "userClarification", "算了，改去三亚",
                "_supplementalVersion", 1,
                "supplementalHistory", List.of(Map.of("destination", "杭州", "days", 3)),
                "maxModelCalls", 6, "maxTokens", 12000, "maxNodeExecutions", 32),
                List.of(successCheckpoint(11L, TravelPlanningGraphFactory.EXTRACT + "_v0", fixtureMapper, previous)));
        when(fixture.sentinel().executeModel(any())).thenReturn("""
                {"intent":"NEW_PLAN","confidence":0.93,"reason":"用户更换了目的地"}""");
        try (fixture) {
            fixture.engine().execute(11L);
        }

        assertThat(fixture.startedNodes()).contains(TravelPlanningGraphFactory.EXTRACT + "_v1");
        Map<String, Object> stateAtExtraction = fixture.extractionState().get();
        Map<String, Object> request = (Map<String, Object>) stateAtExtraction.get("request");
        Map<String, Object> data = (Map<String, Object>) stateAtExtraction.get("data");
        assertThat(request.get("prompt")).isEqualTo("算了，改去三亚");
        assertThat(request).doesNotContainKey("userClarification").doesNotContainKey("destination").doesNotContainKey("days");
        assertThat(data).doesNotContainKey("constraintSpec").doesNotContainKey("itinerary");
        assertThat(data).containsEntry("intent", "NEW_PLAN");
    }

    private final ObjectMapper fixtureMapper = new ObjectMapper().findAndRegisterModules();

    private AgentWorkflowCheckpoint successCheckpoint(Long taskId, String nodeId, ObjectMapper mapper,
                                                      NodeExecutionResult output) throws Exception {
        AgentWorkflowCheckpoint checkpoint = new AgentWorkflowCheckpoint();
        checkpoint.setTaskId(taskId);
        checkpoint.setNodeId(nodeId);
        checkpoint.setNodeStatus("SUCCEEDED");
        checkpoint.setAttempt(1);
        checkpoint.setOutputSnapshot(mapper.writeValueAsString(output));
        return checkpoint;
    }

    private Fixture fixture(Long taskId, Map<String, Object> request, List<AgentWorkflowCheckpoint> checkpoints)
            throws Exception {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        CheckpointStore checkpointsStore = mock(CheckpointStore.class);
        AgentProgressEventStore events = mock(AgentProgressEventStore.class);
        ChatModel chatModel = mock(ChatModel.class);
        SentinelGovernanceService sentinel = mock(SentinelGovernanceService.class);
        ObjectMapper mapper = fixtureMapper;
        TravelWorkflowNodeCatalog catalog = new TravelWorkflowNodeCatalog(chatModel, mapper,
                mock(ObjectProvider.class), mock(ObjectProvider.class), sentinel, events,
                new PlatformObservability());
        AgentTask task = new AgentTask();
        task.setId(taskId);
        task.setStatus("RUNNING");
        task.setWorkflowVersion("formal-travel-stategraph-v4");
        task.setModelCallsUsed(0);
        task.setTokensUsed(0);
        task.setNodeExecutionsUsed(0);
        task.setCancelRequested(false);
        task.setRequestJson(mapper.writeValueAsString(request));
        when(tasks.claimQueuedTask(taskId)).thenReturn(1);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(checkpointsStore.list(taskId)).thenReturn(checkpoints);
        when(checkpointsStore.latest(eq(taskId), anyString())).thenAnswer(invocation -> checkpoints.stream()
                .filter(value -> invocation.getArgument(1).equals(value.getNodeId())).findFirst().orElse(null));
        List<String> startedNodes = new ArrayList<>();
        Map<String, Object> stateSnapshots = new java.util.LinkedHashMap<>();
        Object[] extractionHolder = new Object[1];
        when(checkpointsStore.start(eq(taskId), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
            NodeExecutor node = invocation.getArgument(1);
            startedNodes.add(node.nodeId());
            stateSnapshots.putIfAbsent(node.nodeId(), invocation.getArgument(4));
            if (node.nodeId().startsWith(TravelPlanningGraphFactory.EXTRACT)) {
                extractionHolder[0] = invocation.getArgument(4);
            }
            AgentWorkflowCheckpoint checkpoint = new AgentWorkflowCheckpoint();
            checkpoint.setTaskId(taskId);
            checkpoint.setNodeId(node.nodeId());
            return checkpoint;
        });

        ThreadPoolTaskExecutor parallelExecutor = new ThreadPoolTaskExecutor();
        parallelExecutor.setCorePoolSize(1);
        parallelExecutor.initialize();
        ExecutorService invocationExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ExplicitTravelWorkflowEngine engine = new ExplicitTravelWorkflowEngine(tasks, checkpointsStore,
                catalog, events, mapper, parallelExecutor, invocationExecutor, new PlatformObservability(),
                mock(AgentConversationMemoryService.class), new TravelPlanningGraphFactory());
        return new Fixture(engine, tasks, checkpointsStore, chatModel, sentinel, startedNodes, stateSnapshots,
                () -> (Map<String, Object>) extractionHolder[0], parallelExecutor, invocationExecutor);
    }

    private record Fixture(ExplicitTravelWorkflowEngine engine, AgentTaskMapper tasks, CheckpointStore checkpoints,
                           ChatModel chatModel, SentinelGovernanceService sentinel, List<String> startedNodes,
                           Map<String, Object> stateSnapshots,
                           java.util.function.Supplier<Map<String, Object>> extractionState,
                           ThreadPoolTaskExecutor parallelExecutor, ExecutorService invocationExecutor)
            implements AutoCloseable {
        @Override
        public void close() {
            invocationExecutor.shutdownNow();
            parallelExecutor.shutdown();
        }
    }
}