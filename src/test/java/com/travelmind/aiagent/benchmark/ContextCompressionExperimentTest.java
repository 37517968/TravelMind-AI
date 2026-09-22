package com.travelmind.aiagent.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 离线上下文压缩实验：衡量字符压缩率、硬约束保持率和最近消息保持率。
 * 这不是特定模型的 Token 统计，也不代表生产摘要质量。
 */
class ContextCompressionExperimentTest {

    @Test
    void compactEnvelopeShouldReduceContextWithoutLosingConstraintsOrRecentTurns() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<EvaluationCase> cases = new ArrayList<>();
        try (var input = getClass().getResourceAsStream("/evaluation/context-compression-eval.jsonl")) {
            assertThat(input).isNotNull();
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (!line.isBlank()) cases.add(mapper.readValue(line, EvaluationCase.class));
                }
            }
        }

        long rawChars = 0;
        long compactChars = 0;
        long constraints = 0;
        long retainedConstraints = 0;
        long recentTurns = 0;
        long retainedRecentTurns = 0;
        for (EvaluationCase testCase : cases) {
            String raw = String.join("\n", testCase.history()) + "\n" + String.join("\n", testCase.recent());
            String compact = compact(testCase, 24);
            rawChars += raw.length();
            compactChars += compact.length();
            for (String constraint : testCase.constraints()) {
                constraints++;
                if (compact.contains(constraint)) retainedConstraints++;
            }
            for (String recent : testCase.recent()) {
                recentTurns++;
                if (compact.contains(recent)) retainedRecentTurns++;
            }
        }

        double compressionRatio = compactChars / (double) rawChars;
        double constraintRetention = retainedConstraints / (double) constraints;
        double recentRetention = retainedRecentTurns / (double) recentTurns;
        System.out.printf("CONTEXT_COMPRESSION cases=%d raw_chars=%d compact_chars=%d compression_ratio=%.3f " +
                        "constraint_retention=%.3f recent_retention=%.3f%n",
                cases.size(), rawChars, compactChars, compressionRatio, constraintRetention, recentRetention);

        assertThat(cases).hasSizeGreaterThanOrEqualTo(10);
        assertThat(compressionRatio).isLessThan(0.70);
        assertThat(constraintRetention).isEqualTo(1.0);
        assertThat(recentRetention).isEqualTo(1.0);
    }

    private String compact(EvaluationCase testCase, int summaryBudget) {
        String history = String.join("", testCase.history());
        String summary = history.substring(0, Math.min(summaryBudget, history.length()));
        return "[C]" + String.join(";", testCase.constraints()) +
                "\n[S]" + summary +
                "\n[R]" + String.join("\n", testCase.recent());
    }

    private record EvaluationCase(String id, List<String> history, List<String> recent,
                                  List<String> constraints) { }
}
