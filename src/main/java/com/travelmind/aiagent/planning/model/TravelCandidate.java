package com.travelmind.aiagent.planning.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 可被约束求解器消费的标准化候选项。 */
public record TravelCandidate(
        String id,
        CandidateType type,
        String name,
        String city,
        long unitCostCents,
        int durationMinutes,
        int capacity,
        List<String> tags,
        boolean available,
        Instant observedAt,
        Instant expiresAt,
        String source,
        Map<String, Object> attributes) {

    public TravelCandidate {
        tags = tags == null ? List.of() : List.copyOf(tags);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        source = source == null ? "UNKNOWN" : source;
    }

    public enum CandidateType {
        TRANSPORT, HOTEL, ATTRACTION, RESTAURANT
    }

    public boolean freshAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }
}
