package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicTravelConstraintSolverTest {
    private final DeterministicTravelConstraintSolver solver = new DeterministicTravelConstraintSolver();

    @Test
    void shouldReturnSatAndSelectCheapestFeasibleCombination() {
        TravelConstraintSpec spec = spec(300_000L);
        TravelCandidateSet candidates = candidates(60_000, 80_000);

        TravelSolverResult result = solver.solve(spec, candidates);

        assertThat(result.status()).isEqualTo(TravelSolverResult.SolverStatus.SAT);
        assertThat(result.selected()).extracting(TravelCandidate::id).contains("hotel-cheap", "museum");
        assertThat(result.totalCostCents()).isLessThanOrEqualTo(spec.maxBudgetCents());
    }

    @Test
    void shouldExposeUnsatCoreAndMinimalBudgetRelaxation() {
        TravelConstraintSpec spec = spec(30_000L);

        TravelSolverResult result = solver.solve(spec, candidates(60_000, 80_000));

        assertThat(result.status()).isEqualTo(TravelSolverResult.SolverStatus.UNSAT);
        assertThat(result.unsatCore()).contains("max_budget");
        assertThat(result.relaxationSuggestions()).anyMatch(item -> item.constraintId().equals("max_budget"));
    }

    private TravelConstraintSpec spec(long budget) {
        return new TravelConstraintSpec("", "上海", null, 3, 1, budget, "CNY", List.of(),
                List.of("博物馆"), List.of(), null, Map.of(), Map.of(), 0);
    }

    private TravelCandidateSet candidates(long cheapHotel, long expensiveHotel) {
        Instant now = Instant.now();
        return new TravelCandidateSet(List.of(), List.of(
                candidate("hotel-expensive", TravelCandidate.CandidateType.HOTEL, expensiveHotel, List.of()),
                candidate("hotel-cheap", TravelCandidate.CandidateType.HOTEL, cheapHotel, List.of())),
                List.of(candidate("museum", TravelCandidate.CandidateType.ATTRACTION, 5_000, List.of("博物馆"))),
                List.of(), now);
    }

    private TravelCandidate candidate(String id, TravelCandidate.CandidateType type, long cost, List<String> tags) {
        Instant now = Instant.now();
        return new TravelCandidate(id, type, id, "上海", cost, 120, 4, tags, true, now,
                now.plusSeconds(600), "TEST", Map.of("priceConfidence", "CONFIRMED"));
    }
}
