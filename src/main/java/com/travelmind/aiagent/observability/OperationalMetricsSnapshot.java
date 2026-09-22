package com.travelmind.aiagent.observability;

import com.travelmind.aiagent.knowledge.mapper.KnowledgeIndexStateMapper;
import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.task.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 周期性读取事实表，避免 Prometheus 每次抓取都直接执行 SQL。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OperationalMetricsSnapshot {
    private static final String[] TASK_STATUSES = {
            "QUEUED", "RUNNING", "WAITING_USER", "SUCCEEDED", "FAILED", "CANCELLED"
    };

    private final AgentTaskMapper taskMapper;
    private final OutboxEventMapper outboxMapper;
    private final KnowledgeIndexStateMapper knowledgeStateMapper;
    private final MeterRegistry registry;
    private final Map<String, AtomicLong> taskCounts = new LinkedHashMap<>();
    private final AtomicLong outboxBacklog = new AtomicLong();
    private final AtomicLong knowledgeFailures = new AtomicLong();
    private final AtomicLong modelTokens24h = new AtomicLong();
    private final AtomicLong modelCalls24h = new AtomicLong();

    @PostConstruct
    void register() {
        for (String status : TASK_STATUSES) {
            AtomicLong value = new AtomicLong();
            taskCounts.put(status, value);
            Gauge.builder("agent.tasks", value, AtomicLong::get).tag("status", status).register(registry);
        }
        Gauge.builder("agent.outbox.backlog", outboxBacklog, AtomicLong::get).register(registry);
        Gauge.builder("knowledge.index.failures", knowledgeFailures, AtomicLong::get).register(registry);
        Gauge.builder("agent.model.tokens.24h", modelTokens24h, AtomicLong::get).register(registry);
        Gauge.builder("agent.model.calls.24h", modelCalls24h, AtomicLong::get).register(registry);
        refresh();
    }

    @Scheduled(fixedDelayString = "${management.metrics.platform-refresh-delay-ms:15000}")
    public void refresh() {
        try {
            taskCounts.forEach((status, value) -> value.set(taskMapper.countByStatus(status)));
            outboxBacklog.set(outboxMapper.countBacklog());
            knowledgeFailures.set(knowledgeStateMapper.countFailed());
            modelTokens24h.set(taskMapper.sumTokensLast24Hours());
            modelCalls24h.set(taskMapper.sumModelCallsLast24Hours());
        } catch (RuntimeException unavailable) {
            log.debug("Operational metric snapshot refresh skipped: {}", unavailable.getMessage());
        }
    }
}
