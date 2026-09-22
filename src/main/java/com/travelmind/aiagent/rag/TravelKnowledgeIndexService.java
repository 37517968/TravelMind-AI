package com.travelmind.aiagent.rag;

import com.travelmind.aiagent.model.entity.TravelKnowledgeEntity;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.model.enums.TravelKnowledgeLevel;
import com.travelmind.aiagent.service.TravelPlanService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.travelmind.aiagent.knowledge.service.KnowledgeHybridSearchService;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 旅行知识索引服务
 * 统一管理三级知识实体、BM25 召回和向量召回
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TravelKnowledgeIndexService {

    private final EmbeddingModel embeddingModel;
    private final TravelKnowledgeEntityLoader entityLoader;
    private final TravelPlanService travelPlanService;

    @Autowired(required = false)
    @Qualifier("travelVectorStore")
    private VectorStore travelVectorStore;

    @Autowired(required = false)
    private KnowledgeHybridSearchService externalHybridSearch;

    private final Map<String, TravelKnowledgeEntity> entityStore = new ConcurrentHashMap<>();
    private final Map<String, Document> documentStore = new ConcurrentHashMap<>();
    private final Map<String, List<String>> tokenStore = new ConcurrentHashMap<>();
    private volatile Map<String, Integer> documentFrequency = Map.of();
    private volatile int totalDocuments = 0;

    @PostConstruct
    public void initialize() {
        if (externalHybridSearch != null) {
            log.info("生产知识检索已切换到 Elasticsearch + PGVector，跳过 JVM BM25 初始化");
            return;
        }
        upsertEntities(entityLoader.loadSeedEntities());
        log.info("旅行知识索引初始化完成，当前实体数：{}", entityStore.size());
    }

    public synchronized int indexPendingPlans(boolean includeComments) {
        return upsertEntities(entityLoader.loadPendingPlanEntities(includeComments));
    }

    public synchronized int indexPlans(Collection<com.travelmind.aiagent.model.entity.TravelPlan> plans, boolean includeComments) {
        return upsertEntities(entityLoader.loadPlanEntities(new ArrayList<>(plans), includeComments));
    }

    public synchronized boolean indexSinglePlan(TravelPlan plan, boolean includeComments) {
        TravelKnowledgeEntity entity = entityLoader.loadPlanEntity(plan, includeComments);
        return entity != null && upsertEntities(List.of(entity)) > 0;
    }

    public TravelPlanService getPlanService() {
        return travelPlanService;
    }

    public synchronized int upsertEntities(List<TravelKnowledgeEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        List<Document> documents = new ArrayList<>(entities.size());
        for (TravelKnowledgeEntity entity : entities) {
            Document document = toDocument(entity);
            entityStore.put(entity.getId(), entity);
            documentStore.put(entity.getId(), document);
            tokenStore.put(entity.getId(), tokenize(document.getText()));
            documents.add(document);
        }

        rebuildDocumentStatistics();

        if (travelVectorStore != null && !documents.isEmpty()) {
            travelVectorStore.add(documents);
        }
        return entities.size();
    }

    public List<Document> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (externalHybridSearch != null) {
            return externalDocuments(KnowledgeSearchRequest.builder().query(query).topK(topK).build());
        }

        QueryScope scope = analyzeScope(query);
        List<ScoredDocument> scoredDocuments;
        if (scope.hasLocation()) {
            scoredDocuments = searchHierarchical(query, scope, topK);
        } else {
            scoredDocuments = searchGlobal(query, topK);
        }

        return scoredDocuments.stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(topK)
                .map(ScoredDocument::document)
                .toList();
    }

    public List<Document> searchByLocation(String query, String city, String district, int topK) {
        if (externalHybridSearch != null) {
            return externalDocuments(KnowledgeSearchRequest.builder().query(query).city(city)
                    .district(district).topK(topK).build());
        }
        QueryScope scope = new QueryScope(city, district);
        return searchHierarchical(query, scope, topK).stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(topK)
                .map(ScoredDocument::document)
                .toList();
    }

    private List<Document> externalDocuments(KnowledgeSearchRequest request) {
        return externalHybridSearch.search(request).hits().stream().map(hit -> {
            Map<String, Object> metadata = new LinkedHashMap<>(hit.getMetadata());
            metadata.put("id", hit.getChunkId());
            metadata.put("chunkId", hit.getChunkId());
            metadata.put("documentId", hit.getDocumentId());
            metadata.put("sourceType", hit.getSourceType());
            metadata.put("sourceId", hit.getSourceId());
            metadata.put("contentVersion", hit.getContentVersion());
            metadata.put("citation", "[source=" + hit.getSourceType() + ":" + hit.getSourceId()
                    + "@v" + hit.getContentVersion() + "]");
            return new Document(hit.getChunkId(), hit.getContent(), metadata);
        }).toList();
    }

    public Map<String, List<Document>> searchAndAggregateCities(String query, int topK) {
        List<ScoredDocument> ranked = searchGlobal(query, topK * 2);
        Map<String, List<Document>> byCity = new LinkedHashMap<>();
        for (ScoredDocument item : ranked) {
            TravelKnowledgeEntity entity = entityStore.get(item.id());
            if (entity == null || entity.getCity() == null) {
                continue;
            }
            byCity.computeIfAbsent(entity.getCity(), key -> new ArrayList<>()).add(item.document());
        }
        return byCity;
    }

    public TravelKnowledgeEntity getEntity(String id) {
        return entityStore.get(id);
    }

    private List<ScoredDocument> searchHierarchical(String query, QueryScope scope, int topK) {
        List<TravelKnowledgeEntity> cityCandidates = entityStore.values().stream()
                .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.CITY)
                .filter(entity -> scope.city == null || scope.city.equals(entity.getCity()))
                .toList();

        List<ScoredDocument> cityHits = rankCandidates(query, TravelKnowledgeLevel.CITY, scope.city, null, cityCandidates, topK);

        List<TravelKnowledgeEntity> districtCandidates = entityStore.values().stream()
                .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.DISTRICT)
                .filter(entity -> scope.city == null || scope.city.equals(entity.getCity()))
                .filter(entity -> scope.district == null || scope.district.equals(entity.getDistrict()))
                .toList();

        if (districtCandidates.isEmpty() && scope.district != null) {
            districtCandidates = entityStore.values().stream()
                    .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.DISTRICT)
                    .filter(entity -> scope.district.equals(entity.getDistrict()))
                    .toList();
        }

        List<ScoredDocument> districtHits = rankCandidates(query, TravelKnowledgeLevel.DISTRICT, scope.city, scope.district, districtCandidates, topK);

        List<TravelKnowledgeEntity> planCandidates = entityStore.values().stream()
                .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.PLAN)
                .filter(entity -> scope.city == null || scope.city.equals(entity.getCity()))
                .filter(entity -> scope.district == null || scope.district.equals(entity.getDistrict()))
                .toList();

        if (planCandidates.isEmpty() && scope.city != null) {
            planCandidates = entityStore.values().stream()
                    .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.PLAN)
                    .filter(entity -> scope.city.equals(entity.getCity()))
                    .toList();
        }

        List<ScoredDocument> planHits = rankCandidates(query, TravelKnowledgeLevel.PLAN, scope.city, scope.district, planCandidates, topK * 2);

        Map<String, ScoredDocument> merged = new LinkedHashMap<>();
        mergeHits(merged, cityHits);
        mergeHits(merged, districtHits);
        mergeHits(merged, planHits);
        return new ArrayList<>(merged.values());
    }

    private List<ScoredDocument> searchGlobal(String query, int topK) {
        List<TravelKnowledgeEntity> planCandidates = entityStore.values().stream()
                .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.PLAN)
                .toList();

        List<ScoredDocument> planHits = rankCandidates(query, TravelKnowledgeLevel.PLAN, null, null, planCandidates, topK * 2);
        Map<String, ScoredDocument> cityDocs = new LinkedHashMap<>();
        for (ScoredDocument hit : planHits) {
            TravelKnowledgeEntity entity = entityStore.get(hit.id());
            if (entity == null || entity.getCity() == null) {
                continue;
            }
            String cityId = cityId(entity.getCity());
            TravelKnowledgeEntity cityEntity = entityStore.get(cityId);
            if (cityEntity != null) {
                cityDocs.putIfAbsent(cityId, new ScoredDocument(cityId, documentStore.get(cityId), 0.0, 0.0, hit.score() * 0.6));
            }
        }

        List<ScoredDocument> merged = new ArrayList<>(planHits);
        merged.addAll(cityDocs.values());
        merged.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return merged;
    }

    private List<ScoredDocument> rankCandidates(String query, TravelKnowledgeLevel level, String city, String district, List<TravelKnowledgeEntity> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<String, Double> vectorScoreMap = buildVectorScoreMap(query, level, city, district, topK);
        List<ScoredDocument> bm25Rank = candidates.stream()
                .map(entity -> score(entity, query, vectorScoreMap))
                .sorted(Comparator.comparingDouble(ScoredDocument::bm25Score).reversed())
                .limit(topK)
                .toList();

        List<ScoredDocument> vectorRank = candidates.stream()
                .map(entity -> score(entity, query, vectorScoreMap))
                .sorted(Comparator.comparingDouble(ScoredDocument::vectorScore).reversed())
                .limit(topK)
                .toList();

        Map<String, ScoredDocument> combined = new LinkedHashMap<>();
        for (ScoredDocument item : bm25Rank) {
            combined.put(item.id(), item);
        }
        for (ScoredDocument item : vectorRank) {
            combined.merge(item.id(), item, (left, right) ->
                    new ScoredDocument(
                            left.id(),
                            left.document(),
                            Math.max(left.bm25Score(), right.bm25Score()),
                            Math.max(left.vectorScore(), right.vectorScore()),
                            Math.max(left.score(), right.score())));
        }

        return combined.values().stream()
                .map(item -> finalizeScore(item, level))
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed())
                .limit(topK)
                .toList();
    }

    private ScoredDocument score(TravelKnowledgeEntity entity, String query, Map<String, Double> vectorScoreMap) {
        List<String> queryTokens = tokenize(query);
        List<String> docTokens = tokenStore.getOrDefault(entity.getId(), List.of());

        double bm25Score = bm25(queryTokens, docTokens);
        double vectorScore = vectorScoreMap.getOrDefault(entity.getId(), 0.0);
        if (vectorScore == 0.0 && travelVectorStore == null) {
            float[] queryEmbedding = embeddingModel.embed(query);
            float[] docEmbedding = embeddingModel.embed(documentStore.get(entity.getId()).getText());
            vectorScore = docEmbedding == null ? 0.0 : cosine(queryEmbedding, docEmbedding);
        }
        double boost = levelWeight(entity.getLevel());

        double finalScore = (bm25Score * 0.45) + (vectorScore * 0.45) + (boost * 0.10);

        return new ScoredDocument(entity.getId(), documentStore.get(entity.getId()), bm25Score, vectorScore, finalScore);
    }

    private ScoredDocument finalizeScore(ScoredDocument scoredDocument, TravelKnowledgeLevel level) {
        TravelKnowledgeEntity entity = entityStore.get(scoredDocument.id());
        if (entity == null) {
            return scoredDocument;
        }
        double boost = levelWeight(level);
        double score = scoredDocument.bm25Score() * 0.45 + scoredDocument.vectorScore() * 0.45 + boost * 0.10;
        if (level == TravelKnowledgeLevel.PLAN) {
            score += 0.05;
        }
        return scoredDocument.withScore(score);
    }

    private void mergeHits(Map<String, ScoredDocument> target, List<ScoredDocument> hits) {
        for (ScoredDocument hit : hits) {
            target.merge(hit.id(), hit, (left, right) ->
                    new ScoredDocument(
                            left.id(),
                            left.document(),
                            Math.max(left.bm25Score(), right.bm25Score()),
                            Math.max(left.vectorScore(), right.vectorScore()),
                            Math.max(left.score(), right.score())));
        }
    }

    private Document toDocument(TravelKnowledgeEntity entity) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "id", entity.getId());
        putIfNotNull(metadata, "title", entity.getTitle());
        putIfNotNull(metadata, "city", entity.getCity());
        putIfNotNull(metadata, "district", entity.getDistrict());
        putIfNotNull(metadata, "level", entity.getLevel() == null ? null : entity.getLevel().name());
        putIfNotNull(metadata, "parent_id", entity.getParentId());
        putIfNotNull(metadata, "planId", entity.getPlanId());
        putIfNotNull(metadata, "travelType", entity.getTravelType());
        putIfNotNull(metadata, "tags", entity.getTags());
        putIfNotNull(metadata, "source", entity.getSource());
        if (entity.getMetadata() != null) {
            entity.getMetadata().forEach((key, value) -> putIfNotNull(metadata, key, value));
        }
        return new Document(entity.getContent(), metadata);
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private void rebuildDocumentStatistics() {
        Map<String, Integer> docFreq = new HashMap<>();
        for (List<String> tokens : tokenStore.values()) {
            for (String token : tokens) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }
        documentFrequency = docFreq;
        totalDocuments = Math.max(1, tokenStore.size());
    }

    private double bm25(List<String> queryTokens, List<String> docTokens) {
        if (queryTokens.isEmpty() || docTokens.isEmpty()) {
            return 0.0;
        }

        Map<String, Long> docTermFrequency = docTokens.stream()
                .collect(Collectors.groupingBy(token -> token, Collectors.counting()));
        double score = 0.0;
        double averageLength = 80.0;
        double k1 = 1.5;
        double b = 0.75;

        for (String token : queryTokens) {
            Long tf = docTermFrequency.get(token);
            if (tf == null || tf <= 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(token, 0);
            double idf = Math.log((totalDocuments - df + 0.5) / (df + 0.5) + 1.0);
            double denominator = tf + k1 * (1 - b + b * (docTokens.size() / averageLength));
            score += idf * (tf * (k1 + 1)) / denominator;
        }
        return score;
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        int size = Math.min(left.length, right.length);
        for (int i = 0; i < size; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private double levelWeight(TravelKnowledgeLevel level) {
        return switch (level) {
            case CITY -> 0.15;
            case DISTRICT -> 0.30;
            case PLAN -> 0.55;
        };
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (char ch : normalized.toCharArray()) {
            if (Character.isLetterOrDigit(ch) || isCjk(ch)) {
                buffer.append(ch);
            } else {
                flushToken(tokens, buffer);
            }
        }
        flushToken(tokens, buffer);
        return tokens.stream()
                .filter(token -> token != null && !token.isBlank())
                .flatMap(token -> expandToken(token).stream())
                .toList();
    }

    private void flushToken(List<String> tokens, StringBuilder buffer) {
        if (buffer.length() > 0) {
            tokens.add(buffer.toString());
            buffer.setLength(0);
        }
    }

    private List<String> expandToken(String token) {
        if (token.length() <= 2) {
            return List.of(token);
        }
        if (token.chars().allMatch(this::isCjk)) {
            List<String> result = new ArrayList<>();
            for (int i = 0; i < token.length() - 1; i++) {
                result.add(token.substring(i, i + 2));
            }
            result.add(token);
            return result;
        }
        return List.of(token);
    }

    private boolean isCjk(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private QueryScope analyzeScope(String query) {
        List<String> cities = entityStore.values().stream()
                .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.CITY)
                .map(TravelKnowledgeEntity::getCity)
                .filter(Objects::nonNull)
                .toList();
        List<String> districts = entityStore.values().stream()
                .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.DISTRICT)
                .map(TravelKnowledgeEntity::getDistrict)
                .filter(Objects::nonNull)
                .toList();

        String city = cities.stream().filter(query::contains).findFirst().orElse(null);
        String district = districts.stream().filter(query::contains).findFirst().orElse(null);
        if (city == null && district != null) {
            city = entityStore.values().stream()
                    .filter(entity -> entity.getLevel() == TravelKnowledgeLevel.DISTRICT)
                    .filter(entity -> district.equals(entity.getDistrict()))
                    .map(TravelKnowledgeEntity::getCity)
                    .findFirst()
                    .orElse(null);
        }
        return new QueryScope(city, district);
    }

    private String cityId(String city) {
        return "city:" + normalize(city);
    }

    private Map<String, Double> buildVectorScoreMap(String query, TravelKnowledgeLevel level, String city, String district, int topK) {
        if (travelVectorStore == null) {
            return Map.of();
        }

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThresholdAll();

        Filter.Expression filterExpression = buildFilterExpression(level, city, district);
        if (filterExpression != null) {
            builder.filterExpression(filterExpression);
        }

        List<Document> documents = travelVectorStore.similaritySearch(builder.build());
        Map<String, Double> scores = new LinkedHashMap<>();
        int size = Math.max(1, documents.size());
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            Object id = document.getMetadata().get("id");
            if (id != null) {
                scores.put(id.toString(), 1.0 - ((double) i / size));
            }
        }
        return scores;
    }

    private Filter.Expression buildFilterExpression(TravelKnowledgeLevel level, String city, String district) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op expression = builder.eq("level", level.name());
        if (city != null && !city.isBlank()) {
            expression = builder.and(builder.eq("level", level.name()), builder.eq("city", city));
        }
        if (district != null && !district.isBlank()) {
            expression = builder.and(builder.eq("level", level.name()), builder.eq("district", district));
            if (city != null && !city.isBlank()) {
                expression = builder.and(
                        builder.and(builder.eq("level", level.name()), builder.eq("city", city)),
                        builder.eq("district", district));
            }
        }
        return expression.build();
    }

    private String normalize(String value) {
        return Objects.requireNonNullElse(value, "")
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_");
    }

    private record QueryScope(String city, String district) {
        boolean hasLocation() {
            return city != null || district != null;
        }
    }

    private record ScoredDocument(String id, Document document, double bm25Score, double vectorScore, double score) {
        ScoredDocument withScore(double newScore) {
            return new ScoredDocument(id, document, bm25Score, vectorScore, newScore);
        }
    }
}
