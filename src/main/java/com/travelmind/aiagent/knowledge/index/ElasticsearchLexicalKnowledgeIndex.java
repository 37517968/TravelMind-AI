package com.travelmind.aiagent.knowledge.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchHit;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "travel.knowledge.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ElasticsearchLexicalKnowledgeIndex implements LexicalKnowledgeIndex {
    private final ElasticsearchClient client;
    private final String alias;
    private final String initialIndex;
    private volatile boolean available;

    public ElasticsearchLexicalKnowledgeIndex(ElasticsearchClient client,
                                               @Value("${travel.knowledge.elasticsearch.index-alias:travel-knowledge}") String alias,
                                               @Value("${travel.knowledge.elasticsearch.initial-index:travel-knowledge-v1}") String initialIndex) {
        this.client = client;
        this.alias = alias;
        this.initialIndex = initialIndex;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        try { ensureActiveIndex(); }
        catch (RuntimeException error) { log.warn("Elasticsearch unavailable at startup; hybrid search will degrade: {}", error.getMessage()); }
    }

    @Override
    public void ensureActiveIndex() {
        try {
            if (!client.indices().exists(e -> e.index(initialIndex)).value()) createIndex(initialIndex);
            if (!client.indices().existsAlias(e -> e.name(alias)).value()) {
                client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add.index(initialIndex).alias(alias).isWriteIndex(true))));
            }
            available = true;
        } catch (IOException e) {
            available = false;
            throw new IllegalStateException("Unable to initialize Elasticsearch knowledge index", e);
        }
    }

    @Override
    public void upsert(List<KnowledgeChunk> chunks) { upsertTo(alias, chunks); }

    @Override
    public void upsertTo(String indexName, List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        try {
            client.bulk(b -> {
                for (KnowledgeChunk chunk : chunks) {
                    KnowledgeSearchDocument document = KnowledgeSearchDocument.from(chunk);
                    b.operations(op -> op.index(i -> i.index(indexName).id(chunk.getChunkId()).document(document)));
                }
                return b;
            });
            available = true;
        } catch (IOException e) {
            available = false;
            throw new IllegalStateException("Elasticsearch bulk upsert failed", e);
        }
    }

    @Override
    public void deleteBySource(String sourceType, Long sourceId) {
        try {
            client.deleteByQuery(d -> d.index(alias).query(sourceQuery(sourceType, sourceId)).refresh(true));
        } catch (IOException e) {
            available = false;
            throw new IllegalStateException("Elasticsearch tombstone failed", e);
        }
    }

    @Override
    public List<KnowledgeSearchHit> search(KnowledgeSearchRequest request, int candidates) {
        try {
            SearchResponse<KnowledgeSearchDocument> response = client.search(s -> s.index(alias)
                    .size(Math.max(1, candidates))
                    .query(q -> q.bool(b -> {
                        b.must(m -> m.multiMatch(mm -> mm.query(request.getQuery())
                                .fields("title^3", "content", "tags^1.5", "city^2", "district^2")));
                        b.filter(f -> f.term(t -> t.field("status").value("ACTIVE")));
                        b.filter(f -> f.term(t -> t.field("deleted").value(false)));
                        if (notBlank(request.getCity())) b.filter(f -> f.term(t -> t.field("city").value(request.getCity())));
                        if (notBlank(request.getDistrict())) b.filter(f -> f.term(t -> t.field("district").value(request.getDistrict())));
                        if (notBlank(request.getTravelType())) b.filter(f -> f.term(t -> t.field("travelType").value(request.getTravelType())));
                        if (request.getValidAt() != null) {
                            String at = request.getValidAt().toString();
                            b.filter(f -> f.bool(valid -> valid
                                    .should(option -> option.bool(missing -> missing.mustNot(n -> n.exists(e -> e.field("validFrom")))))
                                    .should(option -> option.range(r -> r.date(d -> d.field("validFrom").lte(at))))
                                    .minimumShouldMatch("1")));
                            b.filter(f -> f.bool(valid -> valid
                                    .should(option -> option.bool(missing -> missing.mustNot(n -> n.exists(e -> e.field("validTo")))))
                                    .should(option -> option.range(r -> r.date(d -> d.field("validTo").gt(at))))
                                    .minimumShouldMatch("1")));
                        }
                        return b;
                    })), KnowledgeSearchDocument.class);
            List<KnowledgeSearchHit> hits = new ArrayList<>();
            int rank = 1;
            for (Hit<KnowledgeSearchDocument> hit : response.hits().hits()) {
                if (hit.source() == null) continue;
                hits.add(toHit(hit.source(), hit.score() == null ? 0.0 : hit.score(), rank++));
            }
            available = true;
            return hits;
        } catch (IOException e) {
            available = false;
            throw new IllegalStateException("Elasticsearch BM25 search failed", e);
        }
    }

    @Override
    public long count() {
        try {
            return client.count(c -> c.index(alias).query(q -> q.bool(b -> b
                    .filter(f -> f.term(t -> t.field("status").value("ACTIVE")))
                    .filter(f -> f.term(t -> t.field("deleted").value(false)))))).count();
        } catch (IOException e) {
            available = false;
            throw new IllegalStateException("Elasticsearch count failed", e);
        }
    }

    @Override
    public void createIndex(String indexName) {
        try {
            if (client.indices().exists(e -> e.index(indexName)).value()) return;
            client.indices().create(c -> c.index(indexName)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m
                            .properties("chunkId", p -> p.keyword(k -> k))
                            .properties("documentId", p -> p.keyword(k -> k))
                            .properties("sourceType", p -> p.keyword(k -> k))
                            .properties("sourceId", p -> p.long_(v -> v))
                            .properties("contentVersion", p -> p.long_(v -> v))
                            .properties("title", p -> p.text(t -> t.analyzer("standard")))
                            .properties("content", p -> p.text(t -> t.analyzer("standard")))
                            .properties("city", p -> p.keyword(k -> k))
                            .properties("district", p -> p.keyword(k -> k))
                            .properties("travelType", p -> p.keyword(k -> k))
                            .properties("tags", p -> p.text(t -> t.analyzer("standard")))
                            .properties("qualityScore", p -> p.double_(v -> v))
                            .properties("likeCount", p -> p.integer(v -> v))
                            .properties("contentHash", p -> p.keyword(k -> k))
                            .properties("status", p -> p.keyword(k -> k))
                            .properties("deleted", p -> p.boolean_(v -> v))
                            .properties("publishedAt", p -> p.date(v -> v))
                            .properties("validFrom", p -> p.date(v -> v))
                            .properties("validTo", p -> p.date(v -> v))));
        } catch (IOException e) {
            throw new IllegalStateException("Elasticsearch index creation failed", e);
        }
    }

    @Override
    public void switchAlias(String indexName) {
        try {
            var aliases = client.indices().getAlias(g -> g.name(alias)).result().keySet();
            client.indices().updateAliases(update -> {
                for (String oldIndex : aliases) {
                    update.actions(a -> a.remove(r -> r.index(oldIndex).alias(alias)));
                }
                update.actions(a -> a.add(add -> add.index(indexName).alias(alias).isWriteIndex(true)));
                return update;
            });
            available = true;
        } catch (IOException e) {
            throw new IllegalStateException("Elasticsearch alias switch failed", e);
        }
    }

    @Override public String activeAlias() { return alias; }
    @Override public boolean available() { return available; }

    private Query sourceQuery(String sourceType, Long sourceId) {
        return Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field("sourceType").value(sourceType)))
                .filter(f -> f.term(t -> t.field("sourceId").value(sourceId)))));
    }

    private KnowledgeSearchHit toHit(KnowledgeSearchDocument d, double score, int rank) {
        return KnowledgeSearchHit.builder().chunkId(d.getChunkId()).documentId(d.getDocumentId())
                .title(d.getTitle()).content(d.getContent()).sourceType(d.getSourceType()).sourceId(d.getSourceId())
                .contentVersion(d.getContentVersion()).city(d.getCity()).district(d.getDistrict())
                .travelType(d.getTravelType()).contentHash(d.getContentHash()).score(score).rank(rank)
                .metadata(Map.of("retriever", "elasticsearch", "qualityScore", d.getQualityScore() == null ? 0 : d.getQualityScore()))
                .build();
    }

    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
}
