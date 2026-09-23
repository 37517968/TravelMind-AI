package com.travelmind.aiagent.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 旅游知识库向量存储配置
 * 用于存储旅行方案、评论等内容，支持 RAG 检索增强
 */
@Configuration
@ConditionalOnProperty(prefix = "travel.knowledge.vectorstore", name = "type", havingValue = "simple", matchIfMissing = true)
public class TravelVectorStoreConfig {

    /**
     * 创建旅游知识库向量存储
     * 使用 SimpleVectorStore 进行内存存储（生产环境可替换为 PgVector 等）
     */
    @Bean("travelVectorStore")
    public VectorStore travelVectorStore(@Qualifier("localTravelEmbeddingModel") EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}

