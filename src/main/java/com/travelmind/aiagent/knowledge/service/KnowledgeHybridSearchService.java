package com.travelmind.aiagent.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.knowledge.index.LexicalKnowledgeIndex;
import com.travelmind.aiagent.knowledge.index.SemanticKnowledgeIndex;
import com.travelmind.aiagent.knowledge.model.*;
import com.travelmind.aiagent.observability.PlatformObservability;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class KnowledgeHybridSearchService {
    private final LexicalKnowledgeIndex lexicalIndex;
    private final SemanticKnowledgeIndex semanticIndex;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformObservability observability;

    @Value("${travel.knowledge.hybrid.rrf-k:60}") private int rrfK;
    @Value("${travel.knowledge.hybrid.lexical-candidates:20}") private int lexicalCandidates;
    @Value("${travel.knowledge.hybrid.vector-candidates:20}") private int vectorCandidates;
    @Value("${travel.knowledge.hybrid.cache-ttl:5m}") private Duration cacheTtl;

    public HybridSearchResponse search(KnowledgeSearchRequest request) {
        Timer.Sample sample = observability.startTimer();
        Observation observation = observability.startRagSearch();
        try (Observation.Scope ignored = observation.openScope()) {
            SearchExecution execution = doSearch(request);
            HybridSearchResponse response = execution.response();
            observability.completeRag(sample, response.hits().isEmpty(), response.degraded(), execution.cacheHit());
            observation.lowCardinalityKeyValue("rag.outcome", response.hits().isEmpty() ? "EMPTY" : "HIT");
            observation.lowCardinalityKeyValue("rag.degraded", Boolean.toString(response.degraded()));
            return response;
        } catch (RuntimeException failure) {
            observation.error(failure);
            observability.completeRag(sample, true, true, false);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    private SearchExecution doSearch(KnowledgeSearchRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return new SearchExecution(new HybridSearchResponse(request.getQuery(), List.of(), List.of(), true,
                    List.of("查询为空")), false);
        }
        String cacheKey = "knowledge:hybrid:" + sha256(request.toString());
        HybridSearchResponse cached = readCache(cacheKey);
        if (cached != null) return new SearchExecution(cached, true);

        List<String> warnings = new ArrayList<>();
        List<KnowledgeSearchHit> lexical = safeSearch("Elasticsearch", warnings,
                () -> lexicalIndex.search(request, lexicalCandidates));
        List<KnowledgeSearchHit> semantic = safeSearch("PGVector", warnings,
                () -> semanticIndex.search(request, vectorCandidates));
        List<KnowledgeSearchHit> fused = fuse(lexical, semantic, Math.max(1, request.getTopK()));
        List<KnowledgeCitation> citations = citations(fused);
        HybridSearchResponse response = new HybridSearchResponse(request.getQuery(), fused, citations,
                lexical.isEmpty() || semantic.isEmpty(), warnings);
        writeCache(cacheKey, response);
        return new SearchExecution(response, false);
    }

    private List<KnowledgeSearchHit> fuse(List<KnowledgeSearchHit> lexical, List<KnowledgeSearchHit> semantic, int topK) {
        Map<String, MutableRank> ranks = new LinkedHashMap<>();
        addRank(ranks, lexical, "elasticsearch");
        addRank(ranks, semantic, "pgvector");
        Set<String> hashes = new HashSet<>();
        List<KnowledgeSearchHit> result = new ArrayList<>();
        List<MutableRank> ordered = ranks.values().stream()
                .sorted(Comparator.comparingDouble(MutableRank::adjustedScore).reversed()).toList();
        for (MutableRank value : ordered) {
            String hash = value.hit.getContentHash();
            if (hash != null && !hash.isBlank() && !hashes.add(hash)) continue;
            int rank = result.size() + 1;
            result.add(KnowledgeSearchHit.builder()
                    .chunkId(value.hit.getChunkId()).documentId(value.hit.getDocumentId()).title(value.hit.getTitle())
                    .content(value.hit.getContent()).sourceType(value.hit.getSourceType()).sourceId(value.hit.getSourceId())
                    .contentVersion(value.hit.getContentVersion()).city(value.hit.getCity()).district(value.hit.getDistrict())
                    .travelType(value.hit.getTravelType()).contentHash(value.hit.getContentHash())
                    .score(value.adjustedScore()).rank(rank)
                    .metadata(Map.of("retrievers", value.retrievers, "rrfScore", value.rrfScore)).build());
            if (result.size() >= topK) break;
        }
        return result;
    }

    private void addRank(Map<String, MutableRank> target, List<KnowledgeSearchHit> hits, String retriever) {
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeSearchHit hit = hits.get(i);
            MutableRank rank = target.computeIfAbsent(hit.getChunkId(), key -> new MutableRank(hit));
            rank.rrfScore += 1.0 / (Math.max(1, rrfK) + i + 1);
            rank.retrievers.add(retriever);
            Object quality = hit.getMetadata().get("qualityScore");
            if (quality instanceof Number number) rank.quality = Math.max(rank.quality, number.doubleValue());
        }
    }

    private List<KnowledgeCitation> citations(List<KnowledgeSearchHit> hits) {
        List<KnowledgeCitation> citations = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeSearchHit hit = hits.get(i);
            String excerpt = hit.getContent() == null ? "" : hit.getContent().substring(0, Math.min(180, hit.getContent().length()));
            String sourceUrl = "TRAVEL_PLAN".equals(hit.getSourceType()) ? "/travel/community/plan/" + hit.getSourceId() : null;
            citations.add(new KnowledgeCitation("K" + (i + 1), hit.getChunkId(), hit.getTitle(), hit.getSourceType(),
                    hit.getSourceId(), hit.getContentVersion(), sourceUrl, excerpt));
        }
        return citations;
    }

    private List<KnowledgeSearchHit> safeSearch(String name, List<String> warnings, SearchCall call) {
        try { return call.search(); }
        catch (RuntimeException error) {
            warnings.add(name + " 检索降级: " + Objects.toString(error.getMessage(), error.getClass().getSimpleName()));
            return List.of();
        }
    }

    private HybridSearchResponse readCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, HybridSearchResponse.class);
        } catch (Exception ignored) { return null; }
    }

    private void writeCache(String key, HybridSearchResponse response) {
        try { redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(response), cacheTtl); }
        catch (Exception ignored) { }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }

    private interface SearchCall { List<KnowledgeSearchHit> search(); }

    private record SearchExecution(HybridSearchResponse response, boolean cacheHit) { }

    private static final class MutableRank {
        private final KnowledgeSearchHit hit;
        private final Set<String> retrievers = new LinkedHashSet<>();
        private double rrfScore;
        private double quality;
        private MutableRank(KnowledgeSearchHit hit) { this.hit = hit; }
        private double adjustedScore() { return rrfScore * (1.0 + Math.min(0.20, quality * 0.20)); }
    }
}
