package com.travelmind.aiagent.task.messaging;

import com.travelmind.aiagent.task.mapper.OutboxEventMapper;
import com.travelmind.aiagent.task.model.OutboxEvent;
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

    @Value("${agent.task.outbox-batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${agent.task.outbox-publish-delay-ms:1000}")
    public void publishPending() {
        for (OutboxEvent event : outboxMapper.selectPublishable(batchSize)) {
            CorrelationData correlation = new CorrelationData(event.getEventId());
            correlation.getFuture().whenComplete((confirm, error) -> {
                if (error == null && confirm != null && confirm.isAck()) {
                    outboxMapper.markPublished(event.getId());
                } else {
                    String reason = error != null ? error.getMessage() : confirm == null ? "missing confirm" : confirm.getReason();
                    outboxMapper.markFailed(event.getId(), truncate(reason));
                }
            });
            try {
                rabbitTemplate.convertAndSend(event.getExchangeName(), event.getRoutingKey(), event.getPayloadJson(), message -> {
                    message.getMessageProperties().setMessageId(event.getEventId());
                    message.getMessageProperties().setContentType("application/json");
                    message.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                    return message;
                }, correlation);
            } catch (RuntimeException ex) {
                outboxMapper.markFailed(event.getId(), truncate(ex.getMessage()));
                log.warn("Outbox event {} publish failed: {}", event.getEventId(), ex.getMessage());
            }
        }
    }

    private String truncate(String value) {
        if (value == null) return "unknown publish error";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
