package com.travelmind.aiagent.knowledge.index;

import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class KnowledgeSearchDocument {
    private String chunkId;
    private String documentId;
    private String sourceType;
    private Long sourceId;
    private Long contentVersion;
    private Integer sequenceNo;
    private String title;
    private String content;
    private String city;
    private String district;
    private String travelType;
    private String tags;
    private Double qualityScore;
    private Integer likeCount;
    private String contentHash;
    private String status;
    private Boolean deleted;
    private LocalDateTime publishedAt;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;

    public static KnowledgeSearchDocument from(KnowledgeChunk chunk) {
        KnowledgeSearchDocument value = new KnowledgeSearchDocument();
        value.setChunkId(chunk.getChunkId());
        value.setDocumentId(chunk.getDocumentId());
        value.setSourceType(chunk.getSourceType());
        value.setSourceId(chunk.getSourceId());
        value.setContentVersion(chunk.getContentVersion());
        value.setSequenceNo(chunk.getSequenceNo());
        value.setTitle(chunk.getTitle());
        value.setContent(chunk.getContent());
        value.setCity(chunk.getCity());
        value.setDistrict(chunk.getDistrict());
        value.setTravelType(chunk.getTravelType());
        value.setTags(chunk.getTags());
        value.setQualityScore(chunk.getQualityScore());
        value.setLikeCount(chunk.getLikeCount());
        value.setContentHash(chunk.getContentHash());
        value.setStatus(chunk.getStatus());
        value.setDeleted(chunk.getIsDeleted());
        value.setPublishedAt(chunk.getPublishedAt());
        value.setValidFrom(chunk.getValidFrom());
        value.setValidTo(chunk.getValidTo());
        return value;
    }
}
