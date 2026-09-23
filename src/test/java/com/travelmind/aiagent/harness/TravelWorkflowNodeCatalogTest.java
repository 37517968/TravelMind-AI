package com.travelmind.aiagent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.rag.TravelKnowledgeIndexService;
import com.travelmind.aiagent.governance.SentinelGovernanceService;
import com.travelmind.aiagent.observability.PlatformObservability;
import com.travelmind.aiagent.task.event.AgentProgressEventStore;
import com.travelmind.aiagent.tool.service.TravelToolFacade;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TravelWorkflowNodeCatalogTest {
    @Test
    void missingPromptShouldPauseForUserInput() throws Exception {
        TravelWorkflowNodeCatalog catalog = catalog();
        WorkflowState state = new WorkflowState(1L, Map.of("prompt", ""));

        NodeExecutionResult extraction = catalog.fixedNode(TravelPlanningGraphFactory.EXTRACT, state).execute(state);
        state.merge(extraction.getData());
        NodeExecutionResult result = catalog.fixedNode(TravelPlanningGraphFactory.CHECK, state).execute(state);

        assertThat(result.getStatus()).isEqualTo("WAITING_USER");
        @SuppressWarnings("unchecked")
        java.util.List<String> missing = (java.util.List<String>) result.getData().get("missingFields");
        assertThat(missing)
                .contains("destination", "maxBudget");
    }

    @Test
    void greetingShouldRouteToChitchatReplyWithoutCallingPlanningNodes() throws Exception {
        TravelWorkflowNodeCatalog catalog = catalog();
        WorkflowState state = new WorkflowState(3L, Map.of("prompt", "你好呀",
                "conversationHistory", List.of(Map.of("role", "user", "content", "你好"))));

        NodeExecutionResult intent = catalog.fixedNode(TravelPlanningGraphFactory.INTENT, state).execute(state);

        assertThat(intent.getData()).containsEntry("workflowRoute", "CHAT")
                .containsEntry("intent", "CHAT").containsEntry("newPlan", false);
        state.merge(intent.getData());
        NodeExecutionResult reply = catalog.fixedNode(TravelPlanningGraphFactory.CHAT_REPLY, state).execute(state);
        assertThat(reply.getData()).containsEntry("responseType", "CHAT");
        assertThat(String.valueOf(reply.getData().get("chatReply"))).contains("TravelMind");
    }

    @Test
    void modelDecisionShouldWinOverHeuristicAndCarveOutNewPlan() throws Exception {
        TravelWorkflowNodeCatalog catalog = catalog("""
                {"intent":"NEW_PLAN","confidence":0.9,"reason":"用户更换目的地"}""");
        WorkflowState state = new WorkflowState(4L, Map.of("prompt", "帮我规划杭州三日游",
                "userClarification", "改成去三亚吧", "_supplementalVersion", 1));

        NodeExecutionResult intent = catalog.fixedNode(TravelPlanningGraphFactory.INTENT, state).execute(state);

        assertThat(intent.getData()).containsEntry("intent", "NEW_PLAN").containsEntry("newPlan", true)
                .containsEntry("intentSource", "MODEL").containsEntry("workflowRoute", "CONTINUE");
        assertThat(intent.getModelCalls()).isEqualTo(1);
    }

    @Test
    void newPlanResetShouldKeepOnlyLatestRequestForExtraction() throws Exception {
        TravelWorkflowNodeCatalog catalog = catalog();
        java.util.Map<String, Object> request = new java.util.HashMap<>();
        request.put("prompt", "帮我规划杭州三日游");
        request.put("userClarification", "重新规划去三亚");
        request.put("destination", "杭州");
        request.put("_supplementalVersion", 2);
        request.put("supplementalHistory", List.of(Map.of("destination", "杭州")));
        WorkflowState state = new WorkflowState(5L, request);

        TravelIntentRouter.resetRequestForNewPlan(state.getRequest());
        NodeExecutionResult extraction = catalog.fixedNode(TravelPlanningGraphFactory.EXTRACT, state).execute(state);

        com.travelmind.aiagent.planning.model.TravelConstraintSpec spec =
                (com.travelmind.aiagent.planning.model.TravelConstraintSpec) extraction.getData().get("constraintSpec");
        assertThat(spec.destination()).isEqualTo("三亚");
        assertThat(state.getRequest()).doesNotContainKey("destination");
    }

    @Test
    void modificationShouldLoadBaseAndOnlyOverrideChangedConstraint() throws Exception {
        TravelWorkflowNodeCatalog catalog = catalog("{\"budget\":5000}");
        Map<String, Object> baseSpec = new java.util.LinkedHashMap<>();
        baseSpec.put("origin", "上海");
        baseSpec.put("destination", "北京");
        baseSpec.put("startDate", null);
        baseSpec.put("days", 4);
        baseSpec.put("travelers", 2);
        baseSpec.put("maxBudgetCents", 300000L);
        baseSpec.put("currency", "CNY");
        baseSpec.put("allowedTransportModes", List.of("TRAIN"));
        baseSpec.put("requiredAttractionTags", List.of("历史"));
        baseSpec.put("requiredCuisineTags", List.of());
        baseSpec.put("hotelMaxNightlyCents", null);
        baseSpec.put("hardConstraints", Map.of());
        baseSpec.put("softPreferences", Map.of());
        baseSpec.put("supplementalVersion", 0);
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("taskType", "MODIFY");
        request.put("prompt", "预算改成5000，其他不变");
        request.put("baseTaskId", 9L);
        request.put("basePlanSnapshot", Map.of("itinerary", "第一天故宫，第二天颐和园。".repeat(10),
                "constraintSpec", baseSpec));
        WorkflowState state = new WorkflowState(9L, request);

        NodeExecutionResult intent = catalog.fixedNode(TravelPlanningGraphFactory.INTENT, state).execute(state);
        assertThat(intent.getData()).containsEntry("workflowRoute", "MODIFY");
        state.merge(intent.getData());
        NodeExecutionResult base = catalog.fixedNode(TravelPlanningGraphFactory.BASE_PLAN, state).execute(state);
        state.merge(base.getData());
        NodeExecutionResult extraction = catalog.fixedNode(TravelPlanningGraphFactory.EXTRACT, state).execute(state);

        var spec = (com.travelmind.aiagent.planning.model.TravelConstraintSpec) extraction.getData().get("constraintSpec");
        assertThat(spec.destination()).isEqualTo("北京");
        assertThat(spec.days()).isEqualTo(4);
        assertThat(spec.travelers()).isEqualTo(2);
        assertThat(spec.maxBudgetCents()).isEqualTo(500000L);
    }

    @Test
    void nodeResultShouldBeCheckpointRoundTripSafe() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        NodeExecutionResult original = NodeExecutionResult.builder()
                .data(Map.of("destination", "上海"))
                .warnings(java.util.List.of("demo"))
                .estimatedTokens(42)
                .build();

        NodeExecutionResult restored = mapper.readValue(mapper.writeValueAsString(original), NodeExecutionResult.class);

        assertThat(restored).isEqualTo(original);
    }

    @SuppressWarnings("unchecked")
    private TravelWorkflowNodeCatalog catalog() {
        return catalog(null);
    }

    /** stubReply 为 null 时 Sentinel 桩直接返回 null，用于验证模型不可用的规则兜底；否则模拟模型返回该文本。 */
    @SuppressWarnings("unchecked")
    private TravelWorkflowNodeCatalog catalog(String stubReply) {
        ObjectProvider<TravelKnowledgeIndexService> knowledge = mock(ObjectProvider.class);
        ObjectProvider<TravelToolFacade> tools = mock(ObjectProvider.class);
        SentinelGovernanceService sentinel = mock(SentinelGovernanceService.class);
        if (stubReply != null) {
            try {
                when(sentinel.executeModel(any())).thenReturn(stubReply);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
        return new TravelWorkflowNodeCatalog(mock(ChatModel.class), new ObjectMapper(), knowledge, tools,
                sentinel, mock(AgentProgressEventStore.class), new PlatformObservability());
    }
}
