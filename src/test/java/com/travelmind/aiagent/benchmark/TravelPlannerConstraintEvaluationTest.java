package com.travelmind.aiagent.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import com.travelmind.aiagent.planning.service.DeterministicTravelConstraintSolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 小型 TravelPlanner 风格离线集：同时覆盖预算、住宿可用性、人数与必选类别。 */
class TravelPlannerConstraintEvaluationTest {
    @Test
    void hardConstraintPassRateShouldBeOneHundredPercentOnRegressionSet() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DeterministicTravelConstraintSolver solver = new DeterministicTravelConstraintSolver();
        List<String> failures = new java.util.ArrayList<>();
        Path dataset = Path.of("src/test/resources/evaluation/travel-planner-constraints.jsonl");
        for (String line : Files.readAllLines(dataset)) {
            if (line.isBlank()) continue;
            Map<String, Object> item = mapper.readValue(line, new TypeReference<>() {});
            TravelSolverResult result = solver.solve(spec(item), candidates(item));
            if (!result.status().name().equals(item.get("expectedStatus"))) failures.add(String.valueOf(item.get("id")));
        }
        assertThat(failures).as("hard-constraint regression failures").isEmpty();
    }

    private TravelConstraintSpec spec(Map<String, Object> item) {
        return new TravelConstraintSpec("", String.valueOf(item.get("destination")), null,
                number(item, "days"), number(item, "travelers"), longNumber(item, "budgetCents"), "CNY",
                List.of(), List.of(String.valueOf(item.get("requiredTag"))), List.of(),
                longOrNull(item, "hotelMaxNightlyCents"),
                Map.of(), Map.of(), 0);
    }

    private TravelCandidateSet candidates(Map<String, Object> item) {
        Instant now = Instant.now();
        TravelCandidate hotel = candidate("hotel-" + item.get("id"), TravelCandidate.CandidateType.HOTEL,
                longNumber(item, "hotelCostCents"), List.of(), Boolean.TRUE.equals(item.get("hotelAvailable")), now);
        TravelCandidate attraction = candidate("attraction-" + item.get("id"), TravelCandidate.CandidateType.ATTRACTION,
                longNumber(item, "attractionCostCents"), List.of(String.valueOf(item.get("candidateTag"))), true, now);
        return new TravelCandidateSet(List.of(), List.of(hotel), List.of(attraction), List.of(), now);
    }

    private TravelCandidate candidate(String id, TravelCandidate.CandidateType type, long cost, List<String> tags,
                                      boolean available, Instant now) {
        return new TravelCandidate(id, type, id, "", cost, 120, 4, tags, available, now,
                now.plusSeconds(600), "EVALUATION_FIXTURE", Map.of("priceConfidence", "CONFIRMED"));
    }

    private int number(Map<String, Object> item, String key) { return ((Number) item.get(key)).intValue(); }
    private long longNumber(Map<String, Object> item, String key) { return ((Number) item.get(key)).longValue(); }

    private Long longOrNull(Map<String, Object> item, String key) {
        return item.get(key) instanceof Number number ? number.longValue() : null;
    }
}
