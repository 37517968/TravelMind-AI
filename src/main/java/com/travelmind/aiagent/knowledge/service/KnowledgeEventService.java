package com.travelmind.aiagent.knowledge.service;

import com.travelmind.aiagent.knowledge.model.KnowledgeIndexCommand;
import com.travelmind.aiagent.task.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.*;

@Service
@RequiredArgsConstructor
public class KnowledgeEventService {
    private final OutboxEventService outbox;

    public void planUpsert(Long planId, Long contentVersion) {
        append("UPSERT", KNOWLEDGE_UPSERT, planId, contentVersion);
    }

    public void planDelete(Long planId, Long contentVersion) {
        append("DELETE", KNOWLEDGE_DELETE, planId, contentVersion);
    }

    private void append(String action, String routingKey, Long sourceId, Long version) {
        String eventId = outbox.newEventId();
        KnowledgeIndexCommand command = new KnowledgeIndexCommand(eventId, action, "TRAVEL_PLAN", sourceId,
                version == null ? 1L : version);
        outbox.append(eventId, KNOWLEDGE_EXCHANGE, routingKey, "TRAVEL_PLAN", sourceId.toString(),
                "KNOWLEDGE_" + action, command);
    }
}
