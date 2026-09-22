package com.travelmind.aiagent.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvaluationDatasetTest {
    @Test
    void datasetShouldContainAtLeastOneHundredValidAndDiverseCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();
        int count = 0;
        try (var input = getClass().getResourceAsStream("/evaluation/travel-rag-eval.jsonl")) {
            assertThat(input).isNotNull();
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.isBlank()) continue;
                    JsonNode item = mapper.readTree(line);
                    assertThat(item.path("query").asText()).isNotBlank();
                    assertThat(ids.add(item.path("id").asText())).isTrue();
                    categories.add(item.path("category").asText());
                    count++;
                }
            }
        }
        assertThat(count).isGreaterThanOrEqualTo(100);
        assertThat(categories).containsExactlyInAnyOrder("destination", "fuzzy_intent", "multi_constraint",
                "freshness", "alias_colloquial", "no_answer", "adversarial");
    }
}
