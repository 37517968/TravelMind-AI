package com.travelmind.aiagent.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointSnapshotSanitizerTest {
    private final CheckpointSnapshotSanitizer sanitizer =
            new CheckpointSnapshotSanitizer(new ObjectMapper());

    @Test
    void shouldKeepBusinessInputAndRedactCredentials() {
        JsonNode result = sanitizer.sanitize("""
                {
                  "prompt":"规划杭州三日游",
                  "conversationId":"conversation-secret",
                  "headers":{"Authorization":"Bearer secret"},
                  "constraintSpec":{"destination":"杭州","days":3}
                }
                """);

        assertThat(result.path("prompt").asText()).isEqualTo("规划杭州三日游");
        assertThat(result.path("constraintSpec").path("destination").asText()).isEqualTo("杭州");
        assertThat(result.path("conversationId").asText()).isEqualTo("[REDACTED]");
        assertThat(result.path("headers").path("Authorization").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void shouldLimitLongText() {
        JsonNode result = sanitizer.sanitize("{\"prompt\":\"" + "a".repeat(5_000) + "\"}");

        assertThat(result.path("prompt").asText()).hasSizeLessThan(4_100).endsWith("[TRUNCATED]");
    }
}
