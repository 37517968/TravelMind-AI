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
        selected = ImmutableValues.list(selected);
        unsatCore = ImmutableValues.list(unsatCore);
        relaxationSuggestions = ImmutableValues.list(relaxationSuggestions);
        diagnostics = ImmutableValues.map(diagnostics);
    }

    public enum SolverStatus { SAT, UNSAT, UNKNOWN }

    public record RelaxationSuggestion(String constraintId, String explanation,
                                       Map<String, Object> proposedChanges, long estimatedImpactCents) {
        public RelaxationSuggestion {
            proposedChanges = ImmutableValues.map(proposedChanges);
        }
    }
}
