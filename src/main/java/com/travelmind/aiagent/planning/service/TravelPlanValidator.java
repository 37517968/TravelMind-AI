package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import com.travelmind.aiagent.planning.model.TravelValidationResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TravelPlanValidator {
    public TravelValidationResult validate(TravelConstraintSpec spec, TravelSolverResult solution) {
        return validate(spec, solution, null);
    }

    public TravelValidationResult validate(TravelConstraintSpec spec, TravelSolverResult solution, String itinerary) {
        List<TravelValidationResult.Violation> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (solution.status() != TravelSolverResult.SolverStatus.SAT)
            violations.add(error("solver_status", "只有 SAT 解可以生成最终行程"));
        if (spec.maxBudgetCents() != null && solution.totalCostCents() > spec.maxBudgetCents())
            violations.add(error("max_budget", "求解结果超过用户预算"));
        if (spec.days() > 1 && solution.selected().stream().noneMatch(item -> item.type() == TravelCandidate.CandidateType.HOTEL))
            violations.add(error("hotel_required", "多日行程缺少住宿候选"));
        for (String tag : spec.requiredAttractionTags()) {
            if (solution.selected().stream().noneMatch(item -> item.type() == TravelCandidate.CandidateType.ATTRACTION
                    && item.tags().stream().anyMatch(value -> value.equalsIgnoreCase(tag))))
                violations.add(error("required_attraction_tags", "缺少必选景点类别: " + tag));
        }
        for (String attraction : spec.specificAttractions()) {
            if (solution.selected().stream().noneMatch(item -> item.type() == TravelCandidate.CandidateType.ATTRACTION
                    && matchesName(item.name(), attraction)))
                violations.add(error("specific_attraction", "缺少用户选定景点: " + attraction));
            if (itinerary != null && !normalizeName(itinerary).contains(normalizeName(attraction)))
                violations.add(error("itinerary_specific_attraction", "最终行程未明确安排选定景点: " + attraction));
        }
        if (solution.selected().stream().anyMatch(item -> "ESTIMATED".equals(item.attributes().get("priceConfidence"))))
            warnings.add("部分价格为估算值，最终提交前必须重新查询库存与价格");
        return new TravelValidationResult(violations.isEmpty(), violations, warnings);
    }

    private TravelValidationResult.Violation error(String id, String message) {
        return new TravelValidationResult.Violation(id, message, TravelValidationResult.Severity.ERROR);
    }

    private boolean matchesName(String candidate, String requested) {
        String left = normalizeName(candidate), right = normalizeName(requested);
        return !left.isBlank() && !right.isBlank() && (left.contains(right) || right.contains(left));
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("[\\s·•()（）\\-—]", "").trim();
    }
}
