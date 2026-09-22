package com.travelmind.aiagent.knowledge;

import com.travelmind.aiagent.knowledge.index.LexicalKnowledgeIndex;
import com.travelmind.aiagent.knowledge.index.SemanticKnowledgeIndex;
import com.travelmind.aiagent.knowledge.model.KnowledgeIndexCommand;
import com.travelmind.aiagent.knowledge.model.KnowledgeIndexState;
import com.travelmind.aiagent.knowledge.service.KnowledgeChunkFactory;
import com.travelmind.aiagent.knowledge.service.KnowledgeIndexPipeline;
import com.travelmind.aiagent.knowledge.service.KnowledgeStateStore;
import com.travelmind.aiagent.mapper.TravelPlanMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class KnowledgeIndexPipelineTest {

    @Test
    void shouldIgnoreOlderUpsertAfterNewerDeleteWasIndexed() {
        Fixture fixture = fixture(indexedState(5L, true));

        fixture.pipeline.process(new KnowledgeIndexCommand(
                "event-v4", "UPSERT", "TRAVEL_PLAN", 42L, 4L));

        verify(fixture.stateStore).state("TRAVEL_PLAN", 42L);
        verifyNoInteractions(fixture.planMapper, fixture.chunkFactory,
                fixture.lexicalIndex, fixture.semanticIndex);
        verifyNoMoreInteractions(fixture.stateStore);
    }

    @Test
    void shouldIgnoreOlderDeleteAfterNewerUpsertWasIndexed() {
        Fixture fixture = fixture(indexedState(8L, false));

        fixture.pipeline.process(new KnowledgeIndexCommand(
                "event-v7", "DELETE", "TRAVEL_PLAN", 42L, 7L));

        verify(fixture.stateStore).state("TRAVEL_PLAN", 42L);
        verifyNoInteractions(fixture.planMapper, fixture.chunkFactory,
                fixture.lexicalIndex, fixture.semanticIndex);
        verifyNoMoreInteractions(fixture.stateStore);
    }

    private static Fixture fixture(KnowledgeIndexState state) {
        TravelPlanMapper planMapper = mock(TravelPlanMapper.class);
        KnowledgeChunkFactory chunkFactory = mock(KnowledgeChunkFactory.class);
        KnowledgeStateStore stateStore = mock(KnowledgeStateStore.class);
        LexicalKnowledgeIndex lexicalIndex = mock(LexicalKnowledgeIndex.class);
        SemanticKnowledgeIndex semanticIndex = mock(SemanticKnowledgeIndex.class);
        when(stateStore.state("TRAVEL_PLAN", 42L)).thenReturn(state);
        KnowledgeIndexPipeline pipeline = new KnowledgeIndexPipeline(
                planMapper, chunkFactory, stateStore, lexicalIndex, semanticIndex);
        return new Fixture(pipeline, planMapper, chunkFactory, stateStore, lexicalIndex, semanticIndex);
    }

    private static KnowledgeIndexState indexedState(long version, boolean deleted) {
        KnowledgeIndexState state = new KnowledgeIndexState();
        state.setSourceType("TRAVEL_PLAN");
        state.setSourceId(42L);
        state.setContentVersion(version);
        state.setIndexStatus("INDEXED");
        state.setIsDeleted(deleted);
        return state;
    }

    private record Fixture(KnowledgeIndexPipeline pipeline,
                           TravelPlanMapper planMapper,
                           KnowledgeChunkFactory chunkFactory,
                           KnowledgeStateStore stateStore,
                           LexicalKnowledgeIndex lexicalIndex,
                           SemanticKnowledgeIndex semanticIndex) {
    }
}
