package com.travelmind.aiagent.knowledge.model;

import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class KnowledgeSearchHit {
    String chunkId;
    String documentId;
    String title;
    String content;
    String sourceType;
    Long sourceId;
    Long contentVersion;
    String city;
    String district;
    String travelType;
    String contentHash;
    double score;
    int rank;
    @Builder.Default Map<String, Object> metadata = Map.of();
}
