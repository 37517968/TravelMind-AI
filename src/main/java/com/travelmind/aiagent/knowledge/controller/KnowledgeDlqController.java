package com.travelmind.aiagent.knowledge.controller;

import com.travelmind.aiagent.annotation.AuthCheck;
import com.travelmind.aiagent.constant.UserConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.KNOWLEDGE_DLQ;
import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.KNOWLEDGE_QUEUE;

@RestController
@RequestMapping("/knowledge/admin/dlq")
@RequiredArgsConstructor
public class KnowledgeDlqController {
    private final RabbitAdmin rabbitAdmin;
    private final RabbitTemplate rabbitTemplate;

    @GetMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Map<String, Object> status() {
        QueueInformation info = rabbitAdmin.getQueueInfo(KNOWLEDGE_DLQ);
        return Map.of("queue", KNOWLEDGE_DLQ,
                "messageCount", info == null ? 0 : info.getMessageCount(),
                "consumerCount", info == null ? 0 : info.getConsumerCount());
    }

    @PostMapping("/replay")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Map<String, Object> replay(@RequestParam(defaultValue = "10") int limit) {
        int replayed = 0;
        for (int i = 0; i < Math.min(Math.max(limit, 1), 100); i++) {
            Message message = rabbitTemplate.receive(KNOWLEDGE_DLQ, 100);
            if (message == null) break;
            message.getMessageProperties().getHeaders().remove("x-knowledge-attempt");
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbitTemplate.send("", KNOWLEDGE_QUEUE, message, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) throw new IllegalStateException(confirm.getReason());
                replayed++;
            } catch (Exception failure) {
                rabbitTemplate.send("", KNOWLEDGE_DLQ, message);
                throw new IllegalStateException("知识索引 DLQ 重放失败，消息已放回死信队列", failure);
            }
        }
        return Map.of("replayed", replayed);
    }
}
