package com.travelmind.aiagent.tool.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelmind.aiagent.tool.mapper.ToolAuditLogMapper;
import com.travelmind.aiagent.tool.model.ToolAuditLog;
import com.travelmind.aiagent.tool.model.ToolExecutionContext;
import com.travelmind.aiagent.tool.model.ToolPolicy;
import com.travelmind.aiagent.tool.model.ToolResult;
import com.travelmind.aiagent.observability.PlatformObservability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.observation.Observation;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ToolAuditService {
    private final ToolAuditLogMapper mapper;
    private final PlatformObservability observability;

    public Observation startObservation(ToolExecutionContext context, ToolPolicy policy) {
        return observability.startTool(context, policy);
    }

    public void record(ToolExecutionContext context, ToolPolicy policy, String argumentsHash,
                       ToolResult result, int attempts, long durationMs) {
        try {
            ToolAuditLog logEntry = new ToolAuditLog();
            logEntry.setRequestId(limit(context.requestId(), 64));
            logEntry.setUserId(limit(context.userId(), 64));
            logEntry.setWorkflowNode(limit(context.workflowNode(), 64));
            logEntry.setToolName(policy.toolName());
            logEntry.setToolSource(policy.source());
            logEntry.setRiskLevel(policy.riskLevel().name());
            logEntry.setArgumentsHash(argumentsHash);
            logEntry.setSuccess(result.success());
            logEntry.setCacheHit(result.cacheHit());
            logEntry.setDegraded(result.degraded());
            logEntry.setAttemptCount(attempts);
            logEntry.setDurationMs(durationMs);
            logEntry.setErrorCode(result.errorCode());
            double estimatedCost = result.cacheHit() || !result.success() ? 0 : policy.estimatedCost();
            logEntry.setEstimatedCost(BigDecimal.valueOf(estimatedCost));
            logEntry.setCreatedAt(LocalDateTime.now());
            mapper.insert(logEntry);
        } catch (RuntimeException auditFailure) {
            log.warn("Tool audit persistence failed for {}: {}", policy.toolName(), auditFailure.getMessage());
        } finally {
            observability.recordTool(policy, result, durationMs);
        }
    }

    public List<Map<String, Object>> last24Hours() {
        List<ToolAuditLog> rows = mapper.selectList(new LambdaQueryWrapper<ToolAuditLog>()
                .ge(ToolAuditLog::getCreatedAt, LocalDateTime.now().minusHours(24))
                .select(ToolAuditLog::getToolName, ToolAuditLog::getDurationMs, ToolAuditLog::getSuccess,
                        ToolAuditLog::getCacheHit, ToolAuditLog::getDegraded, ToolAuditLog::getEstimatedCost));
        return rows.stream().collect(Collectors.groupingBy(ToolAuditLog::getToolName)).entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> summarize(entry.getKey(), entry.getValue()))
                .toList();
    }

    private Map<String, Object> summarize(String toolName, List<ToolAuditLog> rows) {
        List<Long> durations = rows.stream().map(ToolAuditLog::getDurationMs).filter(java.util.Objects::nonNull)
                .sorted().toList();
        long successes = rows.stream().filter(row -> Boolean.TRUE.equals(row.getSuccess())).count();
        long cacheHits = rows.stream().filter(row -> Boolean.TRUE.equals(row.getCacheHit())).count();
        long degraded = rows.stream().filter(row -> Boolean.TRUE.equals(row.getDegraded())).count();
        BigDecimal estimatedCost = rows.stream().map(ToolAuditLog::getEstimatedCost)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("toolName", toolName);
        stats.put("calls", rows.size());
        stats.put("successRate", ratio(successes, rows.size()));
        stats.put("cacheHitRate", ratio(cacheHits, rows.size()));
        stats.put("degradedRate", ratio(degraded, rows.size()));
        stats.put("p50Ms", percentile(durations, 0.50));
        stats.put("p95Ms", percentile(durations, 0.95));
        stats.put("estimatedCost", estimatedCost);
        return stats;
    }

    private double ratio(long value, int total) {
        return total == 0 ? 0 : Math.round(value * 10_000.0 / total) / 100.0;
    }

    private long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private String limit(String value, int length) {
        String safe = value == null || value.isBlank() ? "unknown" : value;
        return safe.substring(0, Math.min(length, safe.length()));
    }
}
