package com.travelmind.aiagent.task.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.travelmind.aiagent.harness.HarnessException;
import com.travelmind.aiagent.harness.WorkflowEngine;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.observability.PlatformObservability;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.*;

@Component
@ConditionalOnProperty(prefix = "app.runtime", name = "agent-worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class AgentTaskConsumer {
    private final ObjectMapper objectMapper;
    private final WorkflowEngine workflowEngine;
    private final AgentTaskMapper taskMapper;
    private final RabbitTemplate rabbitTemplate;
    private final PlatformObservability observability;

    @Value("${agent.task.max-consumer-attempts:3}")
    private int maxAttempts;

    @RabbitListener(queues = PLAN_QUEUE)
    public void consume(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        AgentCommand command = objectMapper.readValue(message.getBody(), AgentCommand.class);
        int attempt = headerAttempt(message);
        Timer.Sample sample = observability.startTimer();
        Observation observation = observability.startTask(command.taskId(), message.getMessageProperties().getMessageId());
        String outcome = "FAILED";
        try (Observation.Scope ignored = observation.openScope()) {
        try {
            workflowEngine.execute(command.taskId());
            var task = taskMapper.selectById(command.taskId());
            outcome = task == null ? "UNKNOWN" : task.getStatus();
            channel.basicAck(deliveryTag, false);
        } catch (HarnessException failure) {
            observation.error(failure);
            if (failure.isRetryable() && attempt < maxAttempts) {
                taskMapper.requeue(command.taskId());
                String retryQueue = attempt == 0 ? RETRY_10_QUEUE : RETRY_60_QUEUE;
                republishConfirmed(retryQueue, message, attempt + 1);
                channel.basicAck(deliveryTag, false);
                outcome = "RETRY";
                log.warn("Task {} scheduled for retry {}, reason={}", command.taskId(), attempt + 1, failure.getMessage());
            } else {
                republishConfirmed(DLQ, message, attempt);
                channel.basicAck(deliveryTag, false);
                outcome = "DLQ";
                log.error("Task {} moved to DLQ after {} attempts", command.taskId(), attempt + 1, failure);
            }
        } catch (Exception infrastructureFailure) {
            // 未能确定是否已安全持久化时不 ACK，让 RabbitMQ 重新投递。
            channel.basicNack(deliveryTag, false, true);
            outcome = "INFRASTRUCTURE_ERROR";
            observation.error(infrastructureFailure);
            throw infrastructureFailure;
        }
        } finally {
            observation.lowCardinalityKeyValue("agent.task.outcome", outcome);
            observation.stop();
            observability.completeTask(sample, outcome);
        }
    }

    private void republishConfirmed(String queue, Message original, int attempt) throws Exception {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setMessageId(original.getMessageProperties().getMessageId());
        properties.setHeader("x-agent-attempt", attempt);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send("", queue, new Message(original.getBody(), properties), correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
        if (!confirm.isAck()) throw new IllegalStateException("重试/DLQ 消息发布未确认: " + confirm.getReason());
    }

    private int headerAttempt(Message message) {
        Object value = message.getMessageProperties().getHeaders().get("x-agent-attempt");
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? 0 : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
