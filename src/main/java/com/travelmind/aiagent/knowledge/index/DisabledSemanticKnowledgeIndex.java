package com.travelmind.aiagent.knowledge.index;

import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchHit;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** 用于本地降级和 RAG 消融实验的显式空向量检索器。 */
@Component
@ConditionalOnProperty(prefix = "travel.knowledge.vectorstore", name = "type",
        havingValue = "disabled", matchIfMissing = true)
public class DisabledSemanticKnowledgeIndex implements SemanticKnowledgeIndex {
    @Override public void upsert(List<KnowledgeChunk> chunks) { }
    @Override public void delete(List<String> chunkIds) { }
    @Override public void deleteBySource(String sourceType, Long sourceId) { }
    @Override public List<KnowledgeSearchHit> search(KnowledgeSearchRequest request, int candidates) { return List.of(); }
    @Override public boolean available() { return false; }
}
