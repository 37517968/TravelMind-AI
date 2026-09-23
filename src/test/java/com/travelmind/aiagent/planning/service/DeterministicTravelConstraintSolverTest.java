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

    @Test
    void unavailableToolCandidatesShouldBeDataGapInsteadOfUnsat() {
        TravelConstraintSpec spec = spec(300_000L);
        TravelCandidateSet candidates = candidates(60_000, 80_000);
        TravelCandidateSet stale = new TravelCandidateSet(candidates.transports(),
                candidates.hotels().stream().map(this::unavailable).toList(),
                candidates.attractions().stream().map(this::unavailable).toList(),
                candidates.restaurants(), candidates.collectedAt());

        TravelSolverResult result = solver.solve(spec, stale);

        assertThat(result.status()).isEqualTo(TravelSolverResult.SolverStatus.SAT);
        assertThat(result.unsatCore()).isEmpty();
        assertThat(result.selected()).extracting(TravelCandidate::id).contains("hotel-cheap", "museum");
        assertThat(String.valueOf(result.diagnostics().get("dataGaps")))
                .contains("hotel_availability", "required_attraction_tags:博物馆");
    }

    @Test
    void verifiedCandidatesViolatingHardConstraintShouldStillBeUnsat() {
        TravelConstraintSpec spec = new TravelConstraintSpec("", "上海", null, 3, 1, 300_000L, "CNY", List.of(),
                List.of(), List.of(), 20_000L, Map.of(), Map.of(), 0);

        TravelSolverResult result = solver.solve(spec, candidates(60_000, 80_000));

        assertThat(result.status()).isEqualTo(TravelSolverResult.SolverStatus.UNSAT);
        assertThat(result.unsatCore()).containsExactly("hotel_availability");
        assertThat(result.diagnostics().get("dataGaps")).isEqualTo(List.of());
    }

    @Test
    void emptyCandidatePoolsShouldReportGapsWithoutClaimingContradiction() {
        TravelSolverResult result = solver.solve(spec(300_000L),
                new TravelCandidateSet(List.of(), List.of(), List.of(), List.of(), Instant.now()));

        assertThat(result.status()).isEqualTo(TravelSolverResult.SolverStatus.SAT);
        assertThat(result.selected()).isEmpty();
        assertThat(String.valueOf(result.diagnostics().get("dataGaps")))
                .contains("hotel_availability", "required_attraction_tags:博物馆");
    }

    @Test
    void dayTripWithFailedToolsShouldStillSelectEstimatesAndReportGap() {
        TravelConstraintSpec dayTrip = new TravelConstraintSpec("", "杭州", null, 1, 1, 300_000L, "CNY", List.of(),
                List.of(), List.of(), null, Map.of(), Map.of(), 0);
        Instant now = Instant.now();
        TravelCandidateSet degraded = new TravelCandidateSet(List.of(),
                List.of(unavailable(candidate("hotel", TravelCandidate.CandidateType.HOTEL, 60_000, List.of()))),
                List.of(unavailable(candidate("west-lake", TravelCandidate.CandidateType.ATTRACTION, 5_000,
                        List.of("通用景点")))), List.of(), now);

        TravelSolverResult result = solver.solve(dayTrip, degraded);

        assertThat(result.status()).isEqualTo(TravelSolverResult.SolverStatus.SAT);
        assertThat(result.selected()).extracting(TravelCandidate::id).containsExactly("west-lake");
        assertThat(String.valueOf(result.diagnostics().get("dataGaps"))).contains("attraction_availability");
    }

    private TravelCandidate unavailable(TravelCandidate item) {
        return new TravelCandidate(item.id(), item.type(), item.name(), item.city(), item.unitCostCents(),
                item.durationMinutes(), item.capacity(), item.tags(), false, item.observedAt(), item.expiresAt(),
                item.source(), item.attributes());
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
