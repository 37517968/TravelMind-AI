package com.travelmind.aiagent.knowledge.index;

import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchHit;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;

import java.util.List;

public interface LexicalKnowledgeIndex {
    void ensureActiveIndex();
    void upsert(List<KnowledgeChunk> chunks);
    void upsertTo(String indexName, List<KnowledgeChunk> chunks);
    void deleteBySource(String sourceType, Long sourceId);
    List<KnowledgeSearchHit> search(KnowledgeSearchRequest request, int candidates);
    long count();
    void createIndex(String indexName);
    void switchAlias(String indexName);
    String activeAlias();
    boolean available();
}
