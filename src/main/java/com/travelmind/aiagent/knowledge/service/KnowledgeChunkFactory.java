package com.travelmind.aiagent.knowledge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.pipeline.TravelSemanticChunker;
import com.travelmind.aiagent.model.entity.TravelKnowledgeEntity;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.rag.TravelKnowledgeEntityLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KnowledgeChunkFactory {
    private final TravelKnowledgeEntityLoader entityLoader;
    private final TravelSemanticChunker chunker;
    private final ObjectMapper objectMapper;

    public List<KnowledgeChunk> fromPlan(TravelPlan plan) {
        TravelKnowledgeEntity entity = entityLoader.loadPlanEntity(plan, true);
        String sanitized = sanitize(entity.getContent());
        List<String> parts = chunker.split(sanitized);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        long version = plan.getKnowledgeVersion() == null ? 1L : plan.getKnowledgeVersion();
        String documentId = "travel-plan:" + plan.getId();
        for (int i = 0; i < parts.size(); i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setChunkId(documentId + ":v" + version + ":c" + i);
            chunk.setDocumentId(documentId);
            chunk.setSourceType("TRAVEL_PLAN");
            chunk.setSourceId(plan.getId());
            chunk.setContentVersion(version);
            chunk.setSequenceNo(i);
            chunk.setTitle(plan.getTitle());
            chunk.setContent(parts.get(i));
            chunk.setCity(entity.getCity());
            chunk.setDistrict(entity.getDistrict());
            chunk.setTravelType(plan.getTravelType());
            chunk.setTags(plan.getTags());
            chunk.setQualityScore(quality(plan));
            chunk.setLikeCount(plan.getLikeCount() == null ? 0 : plan.getLikeCount());
            chunk.setContentHash(sha256(parts.get(i)));
            chunk.setEmbeddingModelVersion("dashscope-text-embedding-v1");
            chunk.setStatus("ACTIVE");
            chunk.setIsDeleted(false);
            chunk.setPublishedAt(plan.getCreateTime());
            chunk.setValidFrom(plan.getUpdateTime() == null ? LocalDateTime.now() : plan.getUpdateTime());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("parentId", entity.getParentId());
            metadata.put("sequenceNo", i);
            metadata.put("sourceUrl", "/travel/community/plan/" + plan.getId());
            chunk.setMetadataJson(json(metadata));
            chunks.add(chunk);
        }
        return chunks;
    }

    public String documentHash(List<KnowledgeChunk> chunks) {
        return sha256(chunks.stream().map(KnowledgeChunk::getContentHash).reduce("", String::concat));
    }

    private String sanitize(String content) {
        if (content == null) return "";
        return content
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[邮箱已脱敏]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[手机号已脱敏]")
                .replaceAll("(?<!\\d)\\d{17}[0-9Xx](?!\\d)", "[证件号已脱敏]");
    }

    private double quality(TravelPlan plan) {
        double completeness = plan.getContent() == null ? 0 : Math.min(0.5, plan.getContent().length() / 2000.0);
        double engagement = Math.min(0.3, Math.log1p(plan.getLikeCount() == null ? 0 : plan.getLikeCount()) / 10.0);
        double comments = Math.min(0.2, Math.log1p(plan.getCommentCount() == null ? 0 : plan.getCommentCount()) / 10.0);
        return Math.min(1.0, completeness + engagement + comments);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Chunk metadata serialization failed", e); }
    }
}
