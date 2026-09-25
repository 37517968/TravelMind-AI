package com.travelmind.aiagent.observability;

import com.travelmind.aiagent.task.mapper.AgentTaskExecutionMapper;
import com.travelmind.aiagent.task.model.AgentTaskExecution;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;

/** 将一次 MQ 驱动的 Agent 执行段与 Trace 关联；记录失败不影响主工作流。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentExecutionRecorder {
    private final AgentTaskExecutionMapper mapper;
    private final Tracer tracer;

    public Handle start(Long taskId, String commandType, String messageId) {
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            Span span = tracer.currentSpan();
            AgentTaskExecution execution = new AgentTaskExecution();
            execution.setTaskId(taskId);
            execution.setCommandType(limit(commandType, 64));
            execution.setMessageId(limit(messageId, 128));
            if (span != null && !span.isNoop()) {
                execution.setTraceId(span.context().traceId());
                execution.setSpanId(span.context().spanId());
            }
            execution.setStatus("RUNNING");
            execution.setWorkerInstance(workerInstance());
            execution.setStartedAt(startedAt);
            mapper.insert(execution);
            return new Handle(execution.getId(), startedAt);
        } catch (RuntimeException failure) {
            log.warn("Unable to persist execution start for task {}: {}", taskId, failure.getMessage());
            return new Handle(null, startedAt);
        }
    }

    public void finish(Handle handle, String status, Throwable error) {
        if (handle == null || handle.id() == null) return;
        try {
            long durationMs = Math.max(0, Duration.between(handle.startedAt(), LocalDateTime.now()).toMillis());
            mapper.finish(handle.id(), limit(status, 32), durationMs,
                    error == null ? null : limit(error.getClass().getSimpleName(), 128),
                    error == null ? null : limit(error.getMessage(), 1024));
        } catch (RuntimeException failure) {
            log.warn("Unable to persist execution finish {}: {}", handle.id(), failure.getMessage());
        }
    }

    private String workerInstance() {
        String configured = System.getenv("HOSTNAME");
        if (configured != null && !configured.isBlank()) return limit(configured, 128);
        try { return limit(InetAddress.getLocalHost().getHostName(), 128); }
        catch (Exception ignored) { return "unknown"; }
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.substring(0, Math.min(max, value.length()));
    }

    public record Handle(Long id, LocalDateTime startedAt) { }
}
