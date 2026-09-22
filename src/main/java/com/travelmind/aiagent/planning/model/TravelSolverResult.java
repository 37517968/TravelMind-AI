package com.travelmind.aiagent.planning.model;

import java.util.List;
import java.util.Map;

public record TravelSolverResult(
        SolverStatus status,
        List<TravelCandidate> selected,
        long totalCostCents,
        List<String> unsatCore,
        List<RelaxationSuggestion> relaxationSuggestions,
        double objectiveScore,
        Map<String, Object> diagnostics) {

    public TravelSolverResult {
        selected = selected == null ? List.of() : List.copyOf(selected);
        unsatCore = unsatCore == null ? List.of() : List.copyOf(unsatCore);
        relaxationSuggestions = relaxationSuggestions == null ? List.of() : List.copyOf(relaxationSuggestions);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public enum SolverStatus { SAT, UNSAT, UNKNOWN }

    public record RelaxationSuggestion(String constraintId, String explanation,
                                       Map<String, Object> proposedChanges, long estimatedImpactCents) {
        public RelaxationSuggestion {
            proposedChanges = proposedChanges == null ? Map.of() : Map.copyOf(proposedChanges);
        }
    }
}
