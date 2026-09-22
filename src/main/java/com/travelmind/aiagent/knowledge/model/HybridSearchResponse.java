package com.travelmind.aiagent.knowledge.model;

import java.util.List;

public record HybridSearchResponse(String originalQuery, List<KnowledgeSearchHit> hits,
                                   List<KnowledgeCitation> citations, boolean degraded,
                                   List<String> warnings) {
}
