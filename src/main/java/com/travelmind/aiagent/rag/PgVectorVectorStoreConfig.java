package com.travelmind.aiagent.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.zaxxer.hikari.HikariDataSource;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
@ConditionalOnProperty(prefix = "travel.knowledge.vectorstore", name = "type", havingValue = "pgvector")
public class PgVectorVectorStoreConfig {

    @Bean("pgVectorDataSource")
    @ConfigurationProperties(prefix = "travel.knowledge.pgvector")
    public HikariDataSource pgVectorDataSource() {
        return new HikariDataSource();
    }

    @Bean("pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(@Qualifier("pgVectorDataSource") HikariDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("travelVectorStore")
    public VectorStore travelVectorStore(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
                                         @Qualifier("dashscopeEmbeddingModel") EmbeddingModel dashscopeEmbeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)                    // Optional: defaults to model dimensions or 1536
                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
                .indexType(HNSW)                     // Optional: defaults to HNSW
                .initializeSchema(true)              // Optional: defaults to false
                .schemaName("public")                // Optional: defaults to "public"
                .vectorTableName("vector_store")     // Optional: defaults to "vector_store"
                .maxDocumentBatchSize(10000)         // Optional: defaults to 10000
                .build();
    }
}
