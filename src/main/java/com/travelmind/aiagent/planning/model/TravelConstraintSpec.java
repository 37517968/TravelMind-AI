package com.travelmind.aiagent.planning.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 与确定性规划器之间的稳定协议。LLM 只能填写该结构，不能生成并执行求解代码。
 */
public record TravelConstraintSpec(
        String origin,
        String destination,
        LocalDate startDate,
        int days,
        int travelers,
        Long maxBudgetCents,
        String currency,
        List<String> allowedTransportModes,
        List<String> requiredAttractionTags,
        List<String> requiredCuisineTags,
        Long hotelMaxNightlyCents,
        Map<String, Object> hardConstraints,
        Map<String, Object> softPreferences,
        int supplementalVersion) {

    public TravelConstraintSpec {
        origin = normalize(origin);
        destination = normalize(destination);
        days = days <= 0 ? 3 : days;
        travelers = travelers <= 0 ? 1 : travelers;
        currency = normalize(currency).isEmpty() ? "CNY" : currency;
        allowedTransportModes = immutable(allowedTransportModes);
        requiredAttractionTags = immutable(requiredAttractionTags);
        requiredCuisineTags = immutable(requiredCuisineTags);
        hardConstraints = ImmutableValues.map(hardConstraints);
        softPreferences = ImmutableValues.map(softPreferences);
    }

    @JsonIgnore
    public List<String> missingRequiredFields() {
        List<String> missing = new ArrayList<>();
        if (destination.isBlank()) missing.add("destination");
        if (maxBudgetCents == null || maxBudgetCents <= 0) missing.add("maxBudget");
        return List.copyOf(missing);
    }

    @JsonIgnore
    public boolean complete() {
        return missingRequiredFields().isEmpty();
    }

    public TravelConstraintSpec merge(Map<String, Object> supplemental) {
        if (supplemental == null || supplemental.isEmpty()) return this;
        Map<String, Object> hard = new LinkedHashMap<>(hardConstraints);
        hard.putAll(supplemental);
        String nextDestination = stringValue(supplemental.getOrDefault("destination", destination));
        Long nextBudget = moneyToCents(supplemental.getOrDefault("maxBudget", supplemental.get("budget")), maxBudgetCents);
        int nextDays = intValue(supplemental.get("days"), days);
        int nextTravelers = intValue(supplemental.get("travelers"), travelers);
        return new TravelConstraintSpec(origin, nextDestination, startDate, nextDays, nextTravelers,
                nextBudget, currency, allowedTransportModes, requiredAttractionTags, requiredCuisineTags,
                hotelMaxNightlyCents, hard, softPreferences, supplementalVersion + 1);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream().map(TravelConstraintSpec::normalize)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String stringValue(Object value) {
        return normalize(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static Long moneyToCents(Object value, Long fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return Math.round(number.doubleValue() * 100D);
        try { return Math.round(Double.parseDouble(value.toString()) * 100D); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
