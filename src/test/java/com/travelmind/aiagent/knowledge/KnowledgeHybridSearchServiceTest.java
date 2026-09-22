package com.travelmind.aiagent.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.knowledge.index.LexicalKnowledgeIndex;
import com.travelmind.aiagent.knowledge.index.SemanticKnowledgeIndex;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchHit;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;
import com.travelmind.aiagent.knowledge.service.KnowledgeHybridSearchService;
import com.travelmind.aiagent.observability.PlatformObservability;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeHybridSearchServiceTest {

    @Test
    void shouldFuseByReciprocalRankAndReturnVersionedCitations() {
        LexicalKnowledgeIndex lexical = mock(LexicalKnowledgeIndex.class);
        SemanticKnowledgeIndex semantic = mock(SemanticKnowledgeIndex.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        KnowledgeHybridSearchService service = new KnowledgeHybridSearchService(
                lexical, semantic, redis, new ObjectMapper(), new PlatformObservability());
        ReflectionTestUtils.setField(service, "rrfK", 60);
        ReflectionTestUtils.setField(service, "lexicalCandidates", 20);
        ReflectionTestUtils.setField(service, "vectorCandidates", 20);
        ReflectionTestUtils.setField(service, "cacheTtl", Duration.ofMinutes(5));

        KnowledgeSearchHit common = hit("c-common", 7L, 3L, "hash-common");
        when(lexical.search(any(), anyInt())).thenReturn(List.of(common, hit("c-keyword", 8L, 1L, "hash-k")));
        when(semantic.search(any(), anyInt())).thenReturn(List.of(hit("c-vector", 9L, 2L, "hash-v"), common));

        var response = service.search(KnowledgeSearchRequest.builder().query("杭州亲子三日游").topK(3).build());

        assertThat(response.hits()).hasSize(3);
        assertThat(response.hits().getFirst().getChunkId()).isEqualTo("c-common");
        assertThat(response.citations().getFirst().sourceId()).isEqualTo(7L);
        assertThat(response.citations().getFirst().contentVersion()).isEqualTo(3L);
        assertThat(response.degraded()).isFalse();
    }

    @Test
    void shouldDegradeWhenOneRetrieverFails() {
        LexicalKnowledgeIndex lexical = mock(LexicalKnowledgeIndex.class);
        SemanticKnowledgeIndex semantic = mock(SemanticKnowledgeIndex.class);
        when(lexical.search(any(), anyInt())).thenThrow(new IllegalStateException("ES down"));
        when(semantic.search(any(), anyInt())).thenReturn(List.of(hit("c-vector", 9L, 2L, "hash-v")));
        KnowledgeHybridSearchService service = new KnowledgeHybridSearchService(
                lexical, semantic, mock(StringRedisTemplate.class), new ObjectMapper(), new PlatformObservability());
        ReflectionTestUtils.setField(service, "rrfK", 60);
        ReflectionTestUtils.setField(service, "lexicalCandidates", 20);
        ReflectionTestUtils.setField(service, "vectorCandidates", 20);
        ReflectionTestUtils.setField(service, "cacheTtl", Duration.ofMinutes(5));

        var response = service.search(KnowledgeSearchRequest.builder().query("西湖路线").topK(3).build());

        assertThat(response.degraded()).isTrue();
        assertThat(response.hits()).hasSize(1);
        assertThat(response.warnings()).anyMatch(message -> message.contains("Elasticsearch"));
    }

    private KnowledgeSearchHit hit(String chunkId, Long sourceId, Long version, String hash) {
        return KnowledgeSearchHit.builder().chunkId(chunkId).documentId("travel-plan:" + sourceId)
                .title("旅行方案").content("可执行的旅行路线").sourceType("TRAVEL_PLAN")
                .sourceId(sourceId).contentVersion(version).contentHash(hash).metadata(Map.of()).build();
    }
}
