package com.travelmind.aiagent.knowledge.model;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class KnowledgeSearchRequest {
    String query;
    String city;
    String district;
    String travelType;
    LocalDateTime validAt;
    @Builder.Default int topK = 5;
}
