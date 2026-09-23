package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
public class TravelFreshnessValidator {
    private final Clock clock;

    public TravelFreshnessValidator() { this(Clock.systemUTC()); }
    TravelFreshnessValidator(Clock clock) { this.clock = clock; }

    public FreshnessResult validate(TravelSolverResult solution) {
        Instant now = clock.instant();
        List<String> stale = solution.selected().stream().filter(item -> !item.freshAt(now))
                .map(TravelCandidate::id).toList();
        // 估算候选本就不承诺实时库存：只有“曾确认过可用性”的候选失去可用性才算过期。
        List<String> unavailable = solution.selected().stream()
                .filter(item -> !item.available())
                .filter(item -> !"ESTIMATED".equals(String.valueOf(item.attributes().get("priceConfidence"))))
                .map(TravelCandidate::id).toList();
        return new FreshnessResult(stale.isEmpty() && unavailable.isEmpty(), stale, unavailable, now);
    }

    public record FreshnessResult(boolean valid, List<String> staleCandidateIds,
                                  List<String> unavailableCandidateIds, Instant checkedAt) {}
}
