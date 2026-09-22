package com.travelmind.aiagent.harness;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;
import java.util.Map;

@Value
@Builder
@Jacksonized
public class PlanningDecision {
    PlannerAction action;
    String reason;
    String toolName;
    @Builder.Default
    Map<String, Object> arguments = Map.of();
    String query;
    String question;
    @Builder.Default
    List<String> missingFields = List.of();
    int requestVersion;
}
