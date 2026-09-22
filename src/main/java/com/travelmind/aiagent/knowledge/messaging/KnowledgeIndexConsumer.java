package com.travelmind.aiagent.knowledge.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.travelmind.aiagent.knowledge.model.KnowledgeIndexCommand;
import com.travelmind.aiagent.knowledge.service.KnowledgeIndexPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.*;

@Component
@ConditionalOnProperty(prefix = "app.runtime", name = "knowledge-worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIndexConsumer {
    private final ObjectMapper objectMapper;
    private final KnowledgeIndexPipeline pipeline;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = KNOWLEDGE_QUEUE)
    public void consume(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        KnowledgeIndexCommand command = objectMapper.readValue(message.getBody(), KnowledgeIndexCommand.class);
        int attempt = attempt(message);
        try {
            pipeline.process(command);
            channel.basicAck(tag, false);
        } catch (RuntimeException failure) {
            try {
                if (attempt < 3) {
                    republish(KNOWLEDGE_RETRY_QUEUE, message, attempt + 1);
                    log.warn("Knowledge event {} scheduled for retry {}: {}", command.eventId(), attempt + 1, failure.getMessage());
                } else {
                    republish(KNOWLEDGE_DLQ, message, attempt);
                    log.error("Knowledge event {} moved to DLQ", command.eventId(), failure);
                }
                channel.basicAck(tag, false);
            } catch (Exception publishFailure) {
                channel.basicNack(tag, false, true);
                throw publishFailure;
            }
        }
    }

    private void republish(String queue, Message original, int attempt) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setMessageId(original.getMessageProperties().getMessageId());
        properties.setHeader("x-knowledge-attempt", attempt);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send("", queue, new Message(original.getBody(), properties), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck()) throw new IllegalStateException("Knowledge retry publish not confirmed: " + confirm.getReason());
    }

    private int attempt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-knowledge-attempt");
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? 0 : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
