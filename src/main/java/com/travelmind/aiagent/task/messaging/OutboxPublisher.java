package com.travelmind.aiagent.task.messaging;

import com.travelmind.aiagent.task.mapper.OutboxEventMapper;
import com.travelmind.aiagent.task.model.OutboxEvent;
import com.travelmind.aiagent.observability.TraceContextCodec;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.COMMAND_EXCHANGE;

@Component
@ConditionalOnProperty(prefix = "app.runtime", name = "agent-worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {
    private final OutboxEventMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final TraceContextCodec traceContextCodec;
    private final Tracer tracer;

    @Value("${agent.task.outbox-batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${agent.task.outbox-publish-delay-ms:1000}")
    public void publishPending() {
        for (OutboxEvent event : outboxMapper.selectPublishable(batchSize)) {
            Span span = traceContextCodec.startProducerSpan(event.getTraceParent(), "agent.outbox.publish", event.getEventId());
            CorrelationData correlation = new CorrelationData(event.getEventId());
            correlation.getFuture().whenComplete((confirm, error) -> {
                if (error == null && confirm != null && confirm.isAck()) {
                    outboxMapper.markPublished(event.getId());
                } else {
                    String reason = error != null ? error.getMessage() : confirm == null ? "missing confirm" : confirm.getReason();
                    outboxMapper.markFailed(event.getId(), truncate(reason));
                }
            });
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                rabbitTemplate.convertAndSend(event.getExchangeName(), event.getRoutingKey(), event.getPayloadJson(), message -> {
                    message.getMessageProperties().setMessageId(event.getEventId());
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                    return message;
                }, correlation);
            } catch (RuntimeException ex) {
                span.error(ex);
                outboxMapper.markFailed(event.getId(), truncate(ex.getMessage()));
                log.warn("Outbox event {} publish failed: {}", event.getEventId(), ex.getMessage());
            } finally {
                span.end();
            }
        }
    }

    private String truncate(String value) {
        if (value == null) return "unknown publish error";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
