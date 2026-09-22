package com.travelmind.aiagent.knowledge.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_index_state")
public class KnowledgeIndexState {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sourceType;
    private Long sourceId;
    private Long contentVersion;
    private String contentHash;
    private String indexStatus;
    private Boolean isDeleted;
    private Integer chunkCount;
    private String embeddingModelVersion;
    private String lastEventId;
    private String lastError;
    private LocalDateTime lastIndexedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
