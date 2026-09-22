package com.travelmind.aiagent.knowledge.index;

import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchHit;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;

import java.util.List;

public interface SemanticKnowledgeIndex {
    void upsert(List<KnowledgeChunk> chunks);
    void delete(List<String> chunkIds);
    void deleteBySource(String sourceType, Long sourceId);
    List<KnowledgeSearchHit> search(KnowledgeSearchRequest request, int candidates);
    boolean available();
}
