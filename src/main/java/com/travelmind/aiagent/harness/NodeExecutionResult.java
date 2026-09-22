package com.travelmind.aiagent.harness;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;
import java.util.Map;

@Value
@Builder
@Jacksonized
public class NodeExecutionResult {
    @Builder.Default
    String status = "SUCCESS";
    @Builder.Default
    Map<String, Object> data = Map.of();
    @Builder.Default
    List<String> evidence = List.of();
    @Builder.Default
    List<String> warnings = List.of();
    @Builder.Default
    Map<String, Object> metrics = Map.of();
    @Builder.Default
    List<String> nextHints = List.of();
    @Builder.Default
    boolean retryable = false;
    @Builder.Default
    int modelCalls = 0;
    @Builder.Default
    int estimatedTokens = 0;
}
