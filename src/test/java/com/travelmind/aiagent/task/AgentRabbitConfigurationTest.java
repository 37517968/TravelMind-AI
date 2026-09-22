package com.travelmind.aiagent.task;

import com.travelmind.aiagent.task.config.AgentRabbitConfiguration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRabbitConfigurationTest {
    private final AgentRabbitConfiguration configuration = new AgentRabbitConfiguration();

    @Test
    void criticalQueuesShouldBeDurableAndMainQueueShouldBeQuorum() {
        var main = configuration.agentPlanQueue();
        var retry = configuration.agentRetry10Queue();
        var dlq = configuration.agentDeadLetterQueue();

        assertThat(main.isDurable()).isTrue();
        assertThat(main.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(retry.getArguments()).containsEntry("x-message-ttl", 10_000);
        assertThat(dlq.getArguments()).containsEntry("x-queue-type", "quorum");

        var knowledge = configuration.knowledgeIndexQueue();
        var knowledgeRetry = configuration.knowledgeRetryQueue();
        var knowledgeDlq = configuration.knowledgeDeadLetterQueue();
        assertThat(knowledge.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(knowledgeRetry.getArguments()).containsEntry("x-message-ttl", 30_000);
        assertThat(knowledgeDlq.getArguments()).containsEntry("x-queue-type", "quorum");
    }
}
