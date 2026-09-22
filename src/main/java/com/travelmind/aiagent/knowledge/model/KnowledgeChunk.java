package com.travelmind.aiagent.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
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
    private String embeddingModelVersion;
    private String status;
    private Boolean isDeleted;
    private LocalDateTime publishedAt;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private String metadataJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
