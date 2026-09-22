package com.travelmind.aiagent.knowledge.index;

import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchHit;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "travel.knowledge.elasticsearch", name = "enabled", havingValue = "false")
public class DisabledLexicalKnowledgeIndex implements LexicalKnowledgeIndex {
    @Override public void ensureActiveIndex() { }
    @Override public void upsert(List<KnowledgeChunk> chunks) { }
    @Override public void upsertTo(String indexName, List<KnowledgeChunk> chunks) { }
    @Override public void deleteBySource(String sourceType, Long sourceId) { }
    @Override public List<KnowledgeSearchHit> search(KnowledgeSearchRequest request, int candidates) { return List.of(); }
    @Override public long count() { return 0; }
    @Override public void createIndex(String indexName) { }
    @Override public void switchAlias(String indexName) { }
    @Override public String activeAlias() { return "disabled"; }
    @Override public boolean available() { return false; }
}
