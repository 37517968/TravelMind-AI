package com.travelmind.aiagent.task.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.task.mapper.OutboxEventMapper;
import com.travelmind.aiagent.task.model.OutboxEvent;
import com.travelmind.aiagent.observability.TraceContextCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxEventService {
    private final OutboxEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final TraceContextCodec traceContextCodec;

    public String append(String exchange, String routingKey, String aggregateType,
                         String aggregateId, String eventType, Object payload) {
        String eventId = UUID.randomUUID().toString();
        append(eventId, exchange, routingKey, aggregateType, aggregateId, eventType, payload);
        return eventId;
    }

    public void append(String eventId, String exchange, String routingKey, String aggregateType,
                       String aggregateId, String eventType, Object payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setExchangeName(exchange);
        event.setRoutingKey(routingKey);
        event.setPayloadJson(json(payload));
        event.setTraceParent(traceContextCodec.capture());
        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setNextRetryAt(LocalDateTime.now());
        mapper.insert(event);
    }

    public String newEventId() {
        return UUID.randomUUID().toString();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Outbox payload serialization failed", e); }
    }
}
