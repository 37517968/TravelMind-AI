package com.travelmind.aiagent.planning.model;

import java.util.List;

public record TravelValidationResult(boolean valid, List<Violation> violations, List<String> warnings) {
    public TravelValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record Violation(String constraintId, String message, Severity severity) {}
    public enum Severity { ERROR, WARNING }
}
