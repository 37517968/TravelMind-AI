package com.travelmind.aiagent.planning.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TravelConstraintExtractorTest {
    private final TravelConstraintExtractor extractor = new TravelConstraintExtractor();

    @Test
    void shouldConvertNaturalLanguageIntoWhitelistedConstraintSpec() {
        var spec = extractor.extract(Map.of("prompt", "两个人去杭州玩5天，预算控制在5000元"));

        assertThat(spec.destination()).isEqualTo("杭州");
        assertThat(spec.days()).isEqualTo(5);
        assertThat(spec.travelers()).isEqualTo(2);
        assertThat(spec.maxBudgetCents()).isEqualTo(500_000L);
        assertThat(spec.complete()).isTrue();
    }

    @Test
    void explicitStructuredFieldsShouldRemainAuthoritative() {
        var spec = extractor.extract(Map.of("prompt", "想出去看看", "destination", "上海", "budget", 3000));

        assertThat(spec.destination()).isEqualTo("上海");
        assertThat(spec.maxBudgetCents()).isEqualTo(300_000L);
    }

    @Test
    void nullValuesFromModelSchemaShouldBeDroppedInsteadOfFailing() {
        Map<String, Object> constraints = new HashMap<>();
        constraints.put("hotelMaxNightly", null);
        constraints.put("seatPreference", "靠窗");
        Map<String, Object> request = new HashMap<>();
        request.put("prompt", "从上海去杭州玩2天，预算1200元");
        request.put("constraints", constraints);

        var spec = extractor.extract(request);

        assertThat(spec.hardConstraints()).doesNotContainKey("hotelMaxNightly")
                .containsEntry("seatPreference", "靠窗");
        assertThat(spec.destination()).isEqualTo("杭州");
        assertThat(spec.complete()).isTrue();
    }
}
