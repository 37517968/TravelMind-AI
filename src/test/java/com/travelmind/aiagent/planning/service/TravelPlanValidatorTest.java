package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TravelPlanValidatorTest {
    private final TravelPlanValidator validator = new TravelPlanValidator();

    @Test
    void finalTextMustContainEverySelectedAttraction() {
        TravelConstraintSpec spec = new TravelConstraintSpec("", "上海", null, 2, 1, 300_000L, "CNY",
                List.of(), List.of(), List.of("外滩", "豫园"), List.of(), null, Map.of(), Map.of(), 1);
        Instant now = Instant.now();
        List<TravelCandidate> selected = List.of(attraction("bund", "外滩", now), attraction("yuyuan", "豫园", now));
        TravelSolverResult solution = new TravelSolverResult(TravelSolverResult.SolverStatus.SAT, selected,
                10_000, List.of(), List.of(), 1, Map.of());

        var invalid = validator.validate(spec, solution, "第一天游览外滩，欣赏黄浦江夜景。");
        var valid = validator.validate(spec, solution, "第一天游览外滩，第二天参观豫园。");

        assertThat(invalid.valid()).isFalse();
        assertThat(invalid.violations()).extracting("constraintId")
                .contains("itinerary_specific_attraction");
        assertThat(valid.valid()).isTrue();
    }

    private TravelCandidate attraction(String id, String name, Instant now) {
        return new TravelCandidate(id, TravelCandidate.CandidateType.ATTRACTION, name, "上海", 5_000,
                120, 0, List.of(), true, now, now.plusSeconds(600), "TEST", Map.of());
    }
}
