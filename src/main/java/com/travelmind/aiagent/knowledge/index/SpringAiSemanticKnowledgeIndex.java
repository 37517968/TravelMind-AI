package com.travelmind.aiagent.knowledge.index;

import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchHit;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "travel.knowledge.vectorstore", name = "type", havingValue = "pgvector")
public class SpringAiSemanticKnowledgeIndex implements SemanticKnowledgeIndex {
    private final VectorStore vectorStore;

    public SpringAiSemanticKnowledgeIndex(@Qualifier("travelVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void upsert(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        // 相同 chunkId 先删后写，兼容不同 VectorStore 的 upsert 语义。
        delete(chunks.stream().map(KnowledgeChunk::getChunkId).toList());
        vectorStore.add(chunks.stream().map(this::toDocument).toList());
    }

    @Override
    public void delete(List<String> chunkIds) {
        if (chunkIds != null && !chunkIds.isEmpty()) vectorStore.delete(chunkIds);
    }

    @Override
    public void deleteBySource(String sourceType, Long sourceId) {
        FilterExpressionBuilder f = new FilterExpressionBuilder();
        vectorStore.delete(f.and(f.eq("sourceType", sourceType), f.eq("sourceId", sourceId)).build());
    }

    @Override
    public List<KnowledgeSearchHit> search(KnowledgeSearchRequest request, int candidates) {
        SearchRequest.Builder builder = SearchRequest.builder().query(request.getQuery())
                .topK(Math.max(1, candidates)).similarityThresholdAll();
        Filter.Expression filter = filter(request);
        if (filter != null) builder.filterExpression(filter);
        List<Document> documents = vectorStore.similaritySearch(builder.build());
        List<KnowledgeSearchHit> hits = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            Map<String, Object> m = document.getMetadata();
            hits.add(KnowledgeSearchHit.builder()
                    .chunkId(string(m.get("chunkId"), document.getId()))
                    .documentId(string(m.get("documentId"), null))
                    .title(string(m.get("title"), "旅行知识"))
                    .content(document.getText())
                    .sourceType(string(m.get("sourceType"), "UNKNOWN"))
                    .sourceId(longValue(m.get("sourceId")))
                    .contentVersion(longValue(m.get("contentVersion")))
                    .city(string(m.get("city"), null)).district(string(m.get("district"), null))
                    .travelType(string(m.get("travelType"), null)).contentHash(string(m.get("contentHash"), null))
                    .score(document.getScore() == null ? 1.0 - ((double) i / Math.max(1, documents.size())) : document.getScore())
                    .rank(i + 1).metadata(Map.of("retriever", "pgvector")).build());
        }
        return hits;
    }

    @Override public boolean available() { return true; }

    private Document toDocument(KnowledgeChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, "chunkId", chunk.getChunkId());
        put(metadata, "documentId", chunk.getDocumentId());
        put(metadata, "title", chunk.getTitle());
        put(metadata, "sourceType", chunk.getSourceType());
        put(metadata, "sourceId", chunk.getSourceId());
        put(metadata, "contentVersion", chunk.getContentVersion());
        put(metadata, "city", chunk.getCity());
        put(metadata, "district", chunk.getDistrict());
        put(metadata, "travelType", chunk.getTravelType());
        put(metadata, "tags", chunk.getTags());
        put(metadata, "contentHash", chunk.getContentHash());
        put(metadata, "status", chunk.getStatus());
        put(metadata, "isDeleted", chunk.getIsDeleted());
        put(metadata, "qualityScore", chunk.getQualityScore());
        put(metadata, "validFrom", chunk.getValidFrom() == null ? null : chunk.getValidFrom().toString());
        put(metadata, "validTo", chunk.getValidTo() == null ? null : chunk.getValidTo().toString());
        return new Document(chunk.getChunkId(), chunk.getContent(), metadata);
    }

    private Filter.Expression filter(KnowledgeSearchRequest request) {
        FilterExpressionBuilder f = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op expression = f.and(f.eq("status", "ACTIVE"), f.eq("isDeleted", false));
        if (notBlank(request.getCity())) expression = f.and(expression, f.eq("city", request.getCity()));
        if (notBlank(request.getDistrict())) expression = f.and(expression, f.eq("district", request.getDistrict()));
        if (notBlank(request.getTravelType())) expression = f.and(expression, f.eq("travelType", request.getTravelType()));
        if (request.getValidAt() != null) {
            String at = request.getValidAt().toString();
            expression = f.and(expression, f.lte("validFrom", at));
        }
        return expression.build();
    }

    private void put(Map<String, Object> map, String key, Object value) { if (value != null) map.put(key, value); }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private String string(Object value, String fallback) { return value == null ? fallback : value.toString(); }
    private Long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? null : Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }
}
