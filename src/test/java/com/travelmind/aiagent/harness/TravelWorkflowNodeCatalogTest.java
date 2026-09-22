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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
        ObjectProvider<TravelKnowledgeIndexService> knowledge = mock(ObjectProvider.class);
        ObjectProvider<TravelToolFacade> tools = mock(ObjectProvider.class);
        return new TravelWorkflowNodeCatalog(mock(ChatModel.class), new ObjectMapper(), knowledge, tools,
                mock(SentinelGovernanceService.class), mock(AgentProgressEventStore.class),
                new PlatformObservability());
    }
}
