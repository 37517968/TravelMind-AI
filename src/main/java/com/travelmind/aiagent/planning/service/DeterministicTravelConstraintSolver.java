package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有限域确定性求解器。接口允许后续无侵入替换为 Z3/CP-SAT 服务；当前实现不执行 LLM 生成代码。
 */
@Component
public class DeterministicTravelConstraintSolver implements TravelConstraintSolver {
    @Override
    public TravelSolverResult solve(TravelConstraintSpec spec, TravelCandidateSet candidates) {
        Set<String> core = new LinkedHashSet<>();
        List<TravelCandidate> selected = new ArrayList<>();

        chooseTransport(spec, candidates, selected, core);
        chooseHotel(spec, candidates, selected, core);
        chooseTagged(spec.requiredAttractionTags(), candidates.attractions(), "required_attraction_tags", selected, core);
        chooseTagged(spec.requiredCuisineTags(), candidates.restaurants(), "required_cuisine_tags", selected, core);
        if (spec.requiredAttractionTags().isEmpty()) {
            candidates.attractions().stream().filter(this::usable).sorted(byCost())
                    .limit(Math.max(1, spec.days())).forEach(selected::add);
        }

        long total = totalCost(spec, selected);
        if (spec.maxBudgetCents() != null && total > spec.maxBudgetCents()) core.add("max_budget");
        if (!core.isEmpty()) return unsat(spec, total, core);
        double score = Math.max(0D, 100D - (total * 100D / Math.max(1L, spec.maxBudgetCents())));
        return new TravelSolverResult(TravelSolverResult.SolverStatus.SAT, selected, total, List.of(), List.of(),
                score, Map.of("solver", "FINITE_DOMAIN_JAVA", "candidateCount", candidates.all().size()));
    }

    private void chooseTransport(TravelConstraintSpec spec, TravelCandidateSet set, List<TravelCandidate> selected,
                                 Set<String> core) {
        List<TravelCandidate> available = set.transports().stream().filter(this::usable)
                .filter(item -> item.capacity() <= 0 || item.capacity() >= spec.travelers())
                .filter(item -> spec.allowedTransportModes().isEmpty() || spec.allowedTransportModes().stream()
                        .anyMatch(mode -> mode.equalsIgnoreCase(String.valueOf(item.attributes().get("mode")))))
                .sorted(byCost()).toList();
        if (available.isEmpty() && (!set.transports().isEmpty() || !spec.origin().isBlank())) core.add("transport_availability");
        else if (!available.isEmpty()) selected.add(available.getFirst());
    }

    private void chooseHotel(TravelConstraintSpec spec, TravelCandidateSet set, List<TravelCandidate> selected,
                             Set<String> core) {
        if (spec.days() <= 1) return;
        List<TravelCandidate> available = set.hotels().stream().filter(this::usable)
                .filter(item -> item.capacity() <= 0 || item.capacity() >= spec.travelers())
                .filter(item -> spec.hotelMaxNightlyCents() == null || item.unitCostCents() <= spec.hotelMaxNightlyCents())
                .sorted(byCost()).toList();
        if (available.isEmpty()) core.add("hotel_availability");
        else selected.add(available.getFirst());
    }

    private void chooseTagged(List<String> required, List<TravelCandidate> pool, String constraint,
                              List<TravelCandidate> selected, Set<String> core) {
        for (String tag : required) {
            TravelCandidate match = pool.stream().filter(this::usable)
                    .filter(item -> item.tags().stream().anyMatch(value -> value.equalsIgnoreCase(tag)))
                    .min(byCost()).orElse(null);
            if (match == null) core.add(constraint + ":" + tag);
            else if (!selected.contains(match)) selected.add(match);
        }
    }

    private TravelSolverResult unsat(TravelConstraintSpec spec, long total, Set<String> core) {
        List<TravelSolverResult.RelaxationSuggestion> suggestions = new ArrayList<>();
        if (core.contains("max_budget")) suggestions.add(new TravelSolverResult.RelaxationSuggestion(
                "max_budget", "当前最低可行组合仍超出预算，可提高预算上限",
                Map.of("budget", Math.ceil(total / 100D)), Math.max(0, total - spec.maxBudgetCents())));
        if (core.contains("hotel_availability")) suggestions.add(new TravelSolverResult.RelaxationSuggestion(
                "hotel_availability", "未找到满足人数和每晚价格的住宿，可提高住宿上限或缩短行程",
                Map.of("relaxHotelMaxNightly", true), 0));
        if (core.stream().anyMatch(item -> item.startsWith("required_")))
            suggestions.add(new TravelSolverResult.RelaxationSuggestion("required_tags",
                    "部分必选类别没有可用候选，可将其从硬约束改为偏好", Map.of("relaxRequiredTags", true), 0));
        if (core.contains("transport_availability")) suggestions.add(new TravelSolverResult.RelaxationSuggestion(
                "transport_availability", "没有满足出行方式或人数的交通候选，可放宽交通方式",
                Map.of("allowedTransportModes", List.of()), 0));
        return new TravelSolverResult(TravelSolverResult.SolverStatus.UNSAT, List.of(), total,
                List.copyOf(core), suggestions, 0D, Map.of("solver", "FINITE_DOMAIN_JAVA"));
    }

    private long totalCost(TravelConstraintSpec spec, List<TravelCandidate> selected) {
        long total = 0;
        for (TravelCandidate item : selected) {
            total += switch (item.type()) {
                case HOTEL -> item.unitCostCents() * Math.max(1, spec.days() - 1) * Math.max(1, (spec.travelers() + 1) / 2);
                case TRANSPORT, ATTRACTION, RESTAURANT -> item.unitCostCents() * spec.travelers();
            };
        }
        return total;
    }

    private boolean usable(TravelCandidate item) { return item.available(); }
    private Comparator<TravelCandidate> byCost() { return Comparator.comparingLong(TravelCandidate::unitCostCents); }
}
