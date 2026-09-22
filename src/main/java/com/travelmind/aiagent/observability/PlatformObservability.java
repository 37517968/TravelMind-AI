package com.travelmind.aiagent.observability;

import com.travelmind.aiagent.tool.model.ToolPolicy;
import com.travelmind.aiagent.tool.model.ToolResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 平台业务指标和 Trace 入口。指标标签只使用有限枚举；taskId/requestId 仅作为 Trace 高基数字段。
 */
@Component
public class PlatformObservability {
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    @Autowired
    public PlatformObservability(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    /** 供不启动 Spring 容器的单元测试使用。 */
    public PlatformObservability() {
        this(new SimpleMeterRegistry(), ObservationRegistry.NOOP);
    }

    public Timer.Sample startTimer() {
        return Timer.start(meters);
    }

    public Observation startTask(Long taskId, String messageId) {
        return Observation.createNotStarted("agent.task.execute", observations)
                .lowCardinalityKeyValue("messaging.system", "rabbitmq")
                .highCardinalityKeyValue("agent.task.id", Objects.toString(taskId, "unknown"))
                .highCardinalityKeyValue("messaging.message.id", safe(messageId))
                .start();
    }

    public void completeTask(Timer.Sample sample, String outcome) {
        String safeOutcome = finite(outcome, "UNKNOWN");
        sample.stop(Timer.builder("agent.task.duration").tag("outcome", safeOutcome).register(meters));
        Counter.builder("agent.task.completed").tag("outcome", safeOutcome).register(meters).increment();
    }

    public Observation startNode(Long taskId, String nodeId) {
        return Observation.createNotStarted("agent.workflow.node", observations)
                .lowCardinalityKeyValue("agent.node.id", finite(nodeId, "UNKNOWN"))
                .highCardinalityKeyValue("agent.task.id", Objects.toString(taskId, "unknown"))
                .start();
    }

    public void completeNode(Timer.Sample sample, String nodeId, String outcome) {
        sample.stop(Timer.builder("agent.node.duration")
                .tag("node", finite(nodeId, "UNKNOWN"))
                .tag("outcome", finite(outcome, "UNKNOWN")).register(meters));
        Counter.builder("agent.node.completed")
                .tag("node", finite(nodeId, "UNKNOWN"))
                .tag("outcome", finite(outcome, "UNKNOWN")).register(meters).increment();
    }

    public Observation startRagSearch() {
        return Observation.start("rag.hybrid.search", observations);
    }

    public void completeRag(Timer.Sample sample, boolean empty, boolean degraded, boolean cacheHit) {
        String outcome = empty ? "EMPTY" : "HIT";
        sample.stop(Timer.builder("rag.search.duration")
                .tag("outcome", outcome).tag("degraded", Boolean.toString(degraded))
                .tag("cache_hit", Boolean.toString(cacheHit)).register(meters));
        Counter.builder("rag.search")
                .tag("outcome", outcome).tag("degraded", Boolean.toString(degraded))
                .tag("cache_hit", Boolean.toString(cacheHit)).register(meters).increment();
    }

    public void recordTool(ToolPolicy policy, ToolResult result, long durationMs) {
        String outcome = result.success() ? "SUCCESS" : "FAILED";
        String errorCode = result.errorCode() == null ? "NONE" : finite(result.errorCode(), "OTHER");
        Counter.builder("tool.calls")
                .tag("tool", finite(policy.toolName(), "unknown"))
                .tag("source", finite(policy.source(), "unknown"))
                .tag("outcome", outcome)
                .tag("error_code", errorCode)
                .tag("cache_hit", Boolean.toString(result.cacheHit()))
                .tag("degraded", Boolean.toString(result.degraded()))
                .register(meters).increment();
        Timer.builder("tool.duration")
                .tag("tool", finite(policy.toolName(), "unknown"))
                .tag("outcome", outcome).register(meters)
                .record(java.time.Duration.ofMillis(Math.max(0, durationMs)));
        if (!result.cacheHit() && result.success() && policy.estimatedCost() > 0) {
            Counter.builder("tool.estimated.cost").tag("tool", finite(policy.toolName(), "unknown"))
                    .register(meters).increment(policy.estimatedCost());
        }
    }

    public void taskSubmitted(String outcome) {
        Counter.builder("agent.task.submitted").tag("outcome", finite(outcome, "UNKNOWN"))
                .register(meters).increment();
    }

    public void recoveredTask() {
        Counter.builder("agent.task.recovered").register(meters).increment();
    }

    public void recordModelFirstToken(long elapsedNanos) {
        Timer.builder("agent.model.first.token.duration").register(meters)
                .record(java.time.Duration.ofNanos(Math.max(0, elapsedNanos)));
    }

    private String finite(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
