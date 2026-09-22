package com.travelmind.aiagent.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 旅行知识混合召回器
 */
@Component
@RequiredArgsConstructor
public class TravelHybridDocumentRetriever implements DocumentRetriever {

    private final TravelKnowledgeIndexService travelKnowledgeIndexService;

    @Override
    public List<Document> retrieve(Query query) {
        return travelKnowledgeIndexService.search(query.text(), 5);
    }

    public DocumentRetriever withTopK(int topK) {
        int effectiveTopK = Math.max(1, topK);
        return query -> travelKnowledgeIndexService.search(query.text(), effectiveTopK);
    }
}
