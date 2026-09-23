package com.travelmind.aiagent.task.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.travelmind.aiagent.task.messaging.AgentMessagingConstants.*;

@Configuration
@EnableRabbit
public class AgentRabbitConfiguration {

    // Spring Boot 3.4 起自动配置的管理 bean 改为 amqpAdmin()，声明类型是 AmqpAdmin，
    // 按 RabbitAdmin 注入会找不到候选，这里显式声明具体类型供 DLQ 运维接口使用。
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public TopicExchange agentCommandExchange() {
        return ExchangeBuilder.topicExchange(COMMAND_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange knowledgeEventExchange() {
        return ExchangeBuilder.topicExchange(KNOWLEDGE_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue agentPlanQueue() {
        return QueueBuilder.durable(PLAN_QUEUE)
                .quorum()
                .withArgument("x-dead-letter-exchange", COMMAND_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DEAD_LETTER)
                .withArgument("x-max-length", 10000)
                .build();
    }

    @Bean
    public Queue agentRetry10Queue() {
        return QueueBuilder.durable(RETRY_10_QUEUE)
                .ttl(10_000)
                .deadLetterExchange(COMMAND_EXCHANGE)
                .deadLetterRoutingKey(PLAN_CREATE)
                .build();
    }

    @Bean
    public Queue agentRetry60Queue() {
        return QueueBuilder.durable(RETRY_60_QUEUE)
                .ttl(60_000)
                .deadLetterExchange(COMMAND_EXCHANGE)
                .deadLetterRoutingKey(PLAN_CREATE)
                .build();
    }

    @Bean
    public Queue agentDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).quorum().build();
    }

    @Bean
    public Binding planCreateBinding(Queue agentPlanQueue, TopicExchange agentCommandExchange) {
        return BindingBuilder.bind(agentPlanQueue).to(agentCommandExchange).with(PLAN_CREATE);
    }

    @Bean
    public Binding planModifyBinding(Queue agentPlanQueue, TopicExchange agentCommandExchange) {
        return BindingBuilder.bind(agentPlanQueue).to(agentCommandExchange).with(PLAN_MODIFY);
    }

    @Bean
    public Binding taskResumeBinding(Queue agentPlanQueue, TopicExchange agentCommandExchange) {
        return BindingBuilder.bind(agentPlanQueue).to(agentCommandExchange).with(TASK_RESUME);
    }

    @Bean
    public Binding deadLetterBinding(Queue agentDeadLetterQueue, TopicExchange agentCommandExchange) {
        return BindingBuilder.bind(agentDeadLetterQueue).to(agentCommandExchange).with(DEAD_LETTER);
    }

    @Bean
    public Queue knowledgeIndexQueue() {
        return QueueBuilder.durable(KNOWLEDGE_QUEUE).quorum()
                .withArgument("x-dead-letter-exchange", KNOWLEDGE_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", KNOWLEDGE_DEAD)
                .withArgument("x-max-length", 20000)
                .build();
    }

    @Bean
    public Queue knowledgeRetryQueue() {
        return QueueBuilder.durable(KNOWLEDGE_RETRY_QUEUE)
                .ttl(30_000)
                .deadLetterExchange(KNOWLEDGE_EXCHANGE)
                .deadLetterRoutingKey(KNOWLEDGE_RETRY)
                .build();
    }

    @Bean
    public Queue knowledgeDeadLetterQueue() {
        return QueueBuilder.durable(KNOWLEDGE_DLQ).quorum().build();
    }

    @Bean
    public Binding knowledgeUpsertBinding(Queue knowledgeIndexQueue, TopicExchange knowledgeEventExchange) {
        return BindingBuilder.bind(knowledgeIndexQueue).to(knowledgeEventExchange).with(KNOWLEDGE_UPSERT);
    }

    @Bean
    public Binding knowledgeDeleteBinding(Queue knowledgeIndexQueue, TopicExchange knowledgeEventExchange) {
        return BindingBuilder.bind(knowledgeIndexQueue).to(knowledgeEventExchange).with(KNOWLEDGE_DELETE);
    }

    @Bean
    public Binding knowledgeRetryBinding(Queue knowledgeIndexQueue, TopicExchange knowledgeEventExchange) {
        return BindingBuilder.bind(knowledgeIndexQueue).to(knowledgeEventExchange).with(KNOWLEDGE_RETRY);
    }

    @Bean
    public Binding knowledgeDeadBinding(Queue knowledgeDeadLetterQueue, TopicExchange knowledgeEventExchange) {
        return BindingBuilder.bind(knowledgeDeadLetterQueue).to(knowledgeEventExchange).with(KNOWLEDGE_DEAD);
    }
}
