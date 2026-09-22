package com.travelmind.aiagent.task.controller;

import com.travelmind.aiagent.annotation.AuthCheck;
import com.travelmind.aiagent.constant.UserConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.*;

@RestController
@RequestMapping("/admin/agent/dlq")
@RequiredArgsConstructor
public class AgentDlqController {
    private final RabbitAdmin rabbitAdmin;
    private final RabbitTemplate rabbitTemplate;

    @GetMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Map<String, Object> status() {
        QueueInformation info = rabbitAdmin.getQueueInfo(DLQ);
        return Map.of("queue", DLQ, "messageCount", info == null ? 0 : info.getMessageCount(),
                "consumerCount", info == null ? 0 : info.getConsumerCount());
    }

    @PostMapping("/replay")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Map<String, Object> replay(@RequestParam(defaultValue = "10") int limit) {
        int replayed = 0;
        for (int i = 0; i < Math.min(Math.max(limit, 1), 100); i++) {
            Message message = rabbitTemplate.receive(DLQ, 100);
            if (message == null) break;
            message.getMessageProperties().getHeaders().remove("x-agent-attempt");
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbitTemplate.send(COMMAND_EXCHANGE, PLAN_CREATE, message, correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirm.isAck()) throw new IllegalStateException(confirm.getReason());
                replayed++;
            } catch (Exception publishFailure) {
                // receive 已确认原 DLQ 消息；发布失败时放回 DLQ，避免人工重放造成消息丢失。
                rabbitTemplate.send("", DLQ, message);
                throw new IllegalStateException("DLQ 重放失败，消息已放回死信队列", publishFailure);
            }
        }
        return Map.of("replayed", replayed);
    }
}
