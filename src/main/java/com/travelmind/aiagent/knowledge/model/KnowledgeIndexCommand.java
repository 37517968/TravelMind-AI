package com.travelmind.aiagent.knowledge.model;

public record KnowledgeIndexCommand(String eventId, String action, String sourceType,
                                    Long sourceId, Long contentVersion) {
}
