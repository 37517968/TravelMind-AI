package com.travelmind.aiagent.observability;

import com.travelmind.aiagent.tool.model.ToolPolicy;
import com.travelmind.aiagent.tool.model.ToolResult;
import com.travelmind.aiagent.tool.model.ToolRiskLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformObservabilityTest {

    @Test
    void shouldPublishOnlyBoundedBusinessMetricDimensions() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PlatformObservability observability = new PlatformObservability(meters, ObservationRegistry.create());

        observability.taskSubmitted("CREATED");
        observability.completeTask(observability.startTimer(), "SUCCEEDED");
        observability.completeNode(observability.startTimer(), "ITINERARY_GENERATION", "SUCCEEDED");
        observability.completeRag(observability.startTimer(), false, true, false);
        observability.recordModelFirstToken(Duration.ofMillis(120).toNanos());
        observability.recordTool(new ToolPolicy("weather", "LOCAL", ToolRiskLevel.READ_ONLY,
                        true, true, Duration.ofMinutes(5), Duration.ofSeconds(3), 2, 4096,
                        Set.of("TOOL_EXECUTION"), 0.02),
                new ToolResult(true, "sunny", "LOCAL", Instant.now(), Instant.now().plusSeconds(60),
                        null, false, false, false, List.of()), 20);

        assertThat(meters.get("agent.task.submitted").tag("outcome", "CREATED").counter().count()).isEqualTo(1);
        assertThat(meters.get("agent.task.duration").tag("outcome", "SUCCEEDED").timer().count()).isEqualTo(1);
        assertThat(meters.get("agent.node.completed").tag("node", "ITINERARY_GENERATION").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("rag.search").tag("degraded", "true").counter().count()).isEqualTo(1);
        assertThat(meters.get("agent.model.first.token.duration").timer().count()).isEqualTo(1);
        assertThat(meters.get("tool.calls").tag("tool", "weather").tag("outcome", "SUCCESS").counter().count())
                .isEqualTo(1);
        assertThat(meters.get("tool.estimated.cost").tag("tool", "weather").counter().count()).isEqualTo(0.02);
        assertThat(meters.getMeters()).noneMatch(meter -> meter.getId().getTags().stream()
                .anyMatch(tag -> tag.getKey().equals("taskId") || tag.getKey().equals("userId")));
    }
}
