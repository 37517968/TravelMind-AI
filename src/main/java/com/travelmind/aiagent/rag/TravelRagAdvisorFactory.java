package com.travelmind.aiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/**
 * 旅行 RAG 增强顾问工厂
 * 
 * 综合运用多种 RAG 增强技术：
 * 1. 多查询扩展（Multi-Query Expansion）- 将用户查询扩展为多个相关查询
 * 2. 查询重写（Query Rewriting）- 优化用户原始查询表述
 * 3. 上下文查询增强（Contextual Query Augmentation）- 将检索结果与查询结合
 * 
 * 这些技术共同作用，显著提升知识检索的召回率和 AI 回复的准确度
 */
@Slf4j
public class TravelRagAdvisorFactory {

    /**
     * 创建基础的旅行 RAG 顾问
     * 
     * @param vectorStore 向量存储
     * @return RAG 顾问
     */
    public static Advisor createBasicAdvisor(VectorStore vectorStore) {
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.5)
                .topK(5)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(TravelContextualQueryAugmenterFactory.createInstance())
                .build();
    }

    /**
     * 创建混合召回顾问
     */
    public static Advisor createHybridAdvisor(DocumentRetriever documentRetriever) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(TravelContextualQueryAugmenterFactory.createInstance())
                .build();
    }

    /**
     * 创建带过滤条件的旅行 RAG 顾问
     * 可以按目的地、旅行类型等条件过滤知识库
     * 
     * @param vectorStore 向量存储
     * @param destination 目的地（可选）
     * @param travelType 旅行类型（可选）
     * @return RAG 顾问
     */
    public static Advisor createFilteredAdvisor(VectorStore vectorStore, String destination, String travelType) {
        VectorStoreDocumentRetriever.Builder retrieverBuilder = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.4)
                .topK(5);

        // 构建过滤条件
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filterExpression = null;

        if (destination != null && !destination.isEmpty()) {
            filterExpression = filterBuilder.eq("destination", destination);
        }

        if (travelType != null && !travelType.isEmpty()) {
            FilterExpressionBuilder.Op typeFilter = filterBuilder.eq("travelType", travelType);
            if (filterExpression != null) {
                filterExpression = filterBuilder.and(filterExpression, typeFilter);
            } else {
                filterExpression = typeFilter;
            }
        }

        if (filterExpression != null) {
            retrieverBuilder.filterExpression(filterExpression.build());
        }

        DocumentRetriever documentRetriever = retrieverBuilder.build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(TravelContextualQueryAugmenterFactory.createInstance())
                .build();
    }

    /**
     * 创建带多查询扩展的高级 RAG 顾问
     * 
     * @param vectorStore 向量存储
     * @param multiQueryExpander 多查询扩展器
     * @return RAG 顾问
     */
    public static Advisor createAdvancedAdvisor(VectorStore vectorStore, MultiQueryExpander multiQueryExpander) {
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.4)
                .topK(5)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryExpander(multiQueryExpander)  // 多查询扩展
                .queryAugmenter(TravelContextualQueryAugmenterFactory.createInstance())
                .build();
    }

    /**
     * 创建宽松模式的 RAG 顾问（即使没有检索到内容也继续回答）
     * 
     * @param vectorStore 向量存储
     * @return RAG 顾问
     */
    public static Advisor createPermissiveAdvisor(VectorStore vectorStore) {
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.3)
                .topK(3)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(TravelContextualQueryAugmenterFactory.createPermissiveInstance())
                .build();
    }

    /**
     * 创建热门方案优先的 RAG 顾问
     * 优先检索点赞数高的方案
     * 
     * @param vectorStore 向量存储
     * @param minLikeCount 最小点赞数
     * @return RAG 顾问
     */
    public static Advisor createPopularFirstAdvisor(VectorStore vectorStore, int minLikeCount) {
        FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op filterExpression = filterBuilder.gte("likeCount", minLikeCount);

        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .filterExpression(filterExpression.build())
                .similarityThreshold(0.4)
                .topK(5)
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(TravelContextualQueryAugmenterFactory.createInstance())
                .build();
    }
}

