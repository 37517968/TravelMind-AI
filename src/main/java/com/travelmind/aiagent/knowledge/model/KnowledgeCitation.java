package com.travelmind.aiagent.knowledge.model;

public record KnowledgeCitation(String citationId, String chunkId, String title,
                                String sourceType, Long sourceId, Long contentVersion,
                                String sourceUrl, String excerpt) {
}
