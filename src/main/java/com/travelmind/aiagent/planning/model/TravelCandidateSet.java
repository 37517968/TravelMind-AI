package com.travelmind.aiagent.planning.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record TravelCandidateSet(
        List<TravelCandidate> transports,
        List<TravelCandidate> hotels,
        List<TravelCandidate> attractions,
        List<TravelCandidate> restaurants,
        Instant collectedAt) {

    public TravelCandidateSet {
        transports = copy(transports);
        hotels = copy(hotels);
        attractions = copy(attractions);
        restaurants = copy(restaurants);
        collectedAt = collectedAt == null ? Instant.now() : collectedAt;
    }

    public List<TravelCandidate> all() {
        List<TravelCandidate> all = new ArrayList<>();
        all.addAll(transports);
        all.addAll(hotels);
        all.addAll(attractions);
        all.addAll(restaurants);
        return List.copyOf(all);
    }

    private static List<TravelCandidate> copy(List<TravelCandidate> values) {
        return ImmutableValues.list(values);
    }
}
