package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有限域确定性求解器。接口允许后续无侵入替换为 Z3/CP-SAT 服务；当前实现不执行 LLM 生成代码。
 *
 * <p>这里严格区分“约束矛盾”和“数据缺口”：只有存在候选却全部违反硬约束才记入 UNSAT core；
 * 工具不可用或没有返回数据时按估算候选降级规划，并把缺口写入 {@code diagnostics.dataGaps}，
 * 避免把外部数据源故障误报成“用户的约束不可同时满足”。
 */
@Component
public class DeterministicTravelConstraintSolver implements TravelConstraintSolver {
    @Override
    public TravelSolverResult solve(TravelConstraintSpec spec, TravelCandidateSet candidates) {
        Set<String> core = new LinkedHashSet<>();
        Set<String> gaps = new LinkedHashSet<>();
        List<TravelCandidate> selected = new ArrayList<>();

        chooseTransport(spec, candidates, selected, core, gaps);
        chooseHotel(spec, candidates, selected, core, gaps);
        chooseTagged(spec.requiredAttractionTags(), candidates.attractions(), "required_attraction_tags", selected, core, gaps);
        chooseTagged(spec.requiredCuisineTags(), candidates.restaurants(), "required_cuisine_tags", selected, core, gaps);
        chooseAttractions(spec, candidates, selected, gaps);

        long total = totalCost(spec, selected);
        if (spec.maxBudgetCents() != null && total > spec.maxBudgetCents()) core.add("max_budget");
        if (!core.isEmpty()) return unsat(spec, total, core, gaps);
        double score = spec.maxBudgetCents() == null || spec.maxBudgetCents() <= 0 ? 0D
                : Math.max(0D, 100D - (total * 100D / spec.maxBudgetCents()));
        return new TravelSolverResult(TravelSolverResult.SolverStatus.SAT, selected, total, List.of(), List.of(),
                score, diagnostics(gaps, candidates.all().size()));
    }

    private void chooseTransport(TravelConstraintSpec spec, TravelCandidateSet set, List<TravelCandidate> selected,
                                 Set<String> core, Set<String> gaps) {
        List<TravelCandidate> feasible = set.transports().stream()
                .filter(item -> item.capacity() <= 0 || item.capacity() >= spec.travelers())
                .filter(item -> spec.allowedTransportModes().isEmpty() || spec.allowedTransportModes().stream()
                        .anyMatch(mode -> mode.equalsIgnoreCase(String.valueOf(item.attributes().get("mode")))))
                .sorted(byCost()).toList();
        if (set.transports().isEmpty()) {
            if (!spec.origin().isBlank()) gaps.add("transport_availability");
            return;
        }
        if (feasible.isEmpty()) core.add("transport_availability");
        else chooseWithFallback("transport_availability", feasible, selected, gaps);
    }

    private void chooseHotel(TravelConstraintSpec spec, TravelCandidateSet set, List<TravelCandidate> selected,
                             Set<String> core, Set<String> gaps) {
        if (spec.days() <= 1) return;
        List<TravelCandidate> feasible = set.hotels().stream()
                .filter(item -> item.capacity() <= 0 || item.capacity() >= spec.travelers())
                .filter(item -> spec.hotelMaxNightlyCents() == null || item.unitCostCents() <= spec.hotelMaxNightlyCents())
                .sorted(byCost()).toList();
        if (set.hotels().isEmpty()) gaps.add("hotel_availability");
        else if (feasible.isEmpty()) core.add("hotel_availability");
        else chooseWithFallback("hotel_availability", feasible, selected, gaps);
    }

    /** 未指定必玩类型时按天数挑最便宜的景点；工具失败导致候选全部未确认时，仍降级选材并记录缺口。 */
    private void chooseAttractions(TravelConstraintSpec spec, TravelCandidateSet set, List<TravelCandidate> selected,
                                   Set<String> gaps) {
        long alreadySelected = selected.stream()
                .filter(item -> item.type() == TravelCandidate.CandidateType.ATTRACTION).count();
        long remaining = Math.max(0, spec.days() * 3L - alreadySelected);
        if (remaining == 0) return;
        // 地图行程默认每天最多三个景点；路线节点会再按日期分组并逐段算路。
        List<TravelCandidate> picks = set.attractions().stream().filter(item -> !selected.contains(item))
                .sorted(byCost()).limit(remaining).toList();
        if (picks.isEmpty()) {
            gaps.add("attraction_availability");
            return;
        }
        if (picks.stream().anyMatch(this::usable)) selected.addAll(picks.stream().filter(this::usable).toList());
        else {
            gaps.add("attraction_availability");
            selected.addAll(picks);
        }
    }

    /**
     * 优先选择有实时观测依据的候选（调用方需按成本升序传入）；只剩未校验估算时降级使用并记录缺口。
     * 已被同一候选满足的约束不会重复加入方案。
     */
    private void chooseWithFallback(String category, List<TravelCandidate> feasible, List<TravelCandidate> selected,
                                    Set<String> gaps) {
        TravelCandidate verified = feasible.stream().filter(this::usable).findFirst().orElse(null);
        if (verified != null) {
            if (!selected.contains(verified)) selected.add(verified);
            return;
        }
        gaps.add(category);
        if (feasible.stream().noneMatch(selected::contains)) selected.add(feasible.getFirst());
    }

    private void chooseTagged(List<String> required, List<TravelCandidate> pool, String constraint,
                              List<TravelCandidate> selected, Set<String> core, Set<String> gaps) {
        for (String tag : required) {
            List<TravelCandidate> matches = pool.stream()
                    .filter(item -> item.tags().stream().anyMatch(value -> value.equalsIgnoreCase(tag)))
                    .sorted(byCost()).toList();
            if (matches.isEmpty()) {
                // 没有任何候选说明数据源没返回数据，只有确有数据却不含该类别才是约束矛盾。
                if (pool.isEmpty()) gaps.add(constraint + ":" + tag);
                else core.add(constraint + ":" + tag);
                continue;
            }
            chooseWithFallback(constraint + ":" + tag, matches, selected, gaps);
        }
    }

    private TravelSolverResult unsat(TravelConstraintSpec spec, long total, Set<String> core, Set<String> gaps) {
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
                List.copyOf(core), suggestions, 0D, diagnostics(gaps, 0));
    }

    private Map<String, Object> diagnostics(Set<String> gaps, int candidateCount) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("solver", "FINITE_DOMAIN_JAVA");
        if (candidateCount > 0) diagnostics.put("candidateCount", candidateCount);
        diagnostics.put("dataGaps", List.copyOf(gaps));
        return diagnostics;
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
