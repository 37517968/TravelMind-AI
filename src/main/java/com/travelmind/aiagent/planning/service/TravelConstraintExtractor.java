package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将请求收敛为白名单 JSON Schema。显式字段优先，中文 Prompt 仅作可审计的兜底提取。
 */
@Component
public class TravelConstraintExtractor {
    private static final Pattern DAYS = Pattern.compile("([一二两三四五六七八九十\\d]{1,2})\\s*[天日]");
    private static final Pattern BUDGET = Pattern.compile(
            "(?:预算(?:改成|调整为|改为|提高到|降到)?|不超过|控制在)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:元|块)?");
    private static final Pattern TRAVELERS = Pattern.compile("([一二两三四五六七八九十\\d]{1,2})\\s*(?:个)?(?:人|位)");
    private static final Pattern DESTINATION = Pattern.compile(
            "(?:去|到|目的地[:：]?)\\s*([\\p{IsHan}]{2,8}?)(?=玩|游|旅行|[，,\\s\\d]|$)");
    private static final Pattern CHANGED_DESTINATION = Pattern.compile(
            "(?:改去|换去|改成|换成|目的地(?:改成|改为))\\s*([\\p{IsHan}]{2,8}?)(?=玩|游|旅行|[，,。\\s\\d]|$)");

    public TravelConstraintSpec extract(Map<String, Object> request) {
        Map<String, Object> safe = request == null ? Map.of() : request;
        Map<String, Object> constraints = mapValue(safe.get("constraints"));
        String prompt = (stringValue(safe.get("prompt")) + " " + stringValue(safe.get("userClarification"))).trim();
        boolean modifying = Boolean.TRUE.equals(safe.get("modificationMode"));
        boolean destinationFromDraft = Boolean.TRUE.equals(safe.get("_destinationFromDraft"));
        boolean budgetFromDraft = Boolean.TRUE.equals(safe.get("_budgetFromDraft"));
        String changedDestination = modifying ? match(prompt, CHANGED_DESTINATION) : "";
        String promptDestination = match(prompt, DESTINATION);
        String destination = destinationFromDraft
                ? firstNonBlank(changedDestination, promptDestination, stringValue(safe.get("destination")),
                        stringValue(constraints.get("destination")))
                : firstNonBlank(changedDestination, stringValue(safe.get("destination")),
                        stringValue(constraints.get("destination")), promptDestination);
        Integer promptDays = optionalIntMatch(prompt, DAYS);
        Integer promptTravelers = optionalIntMatch(prompt, TRAVELERS);
        int days = promptDays == null ? intValue(safe.get("days"), 3) : promptDays;
        int travelers = promptTravelers == null ? intValue(safe.get("travelers"), 1) : promptTravelers;
        Long promptBudget = moneyMatchToCents(prompt);
        Long structuredBudget = moneyToCents(safe.get("budget"));
        Long budget = modifying || budgetFromDraft
                ? (promptBudget != null ? promptBudget : structuredBudget)
                : (structuredBudget != null ? structuredBudget : promptBudget);
        Long hotelMax = moneyToCents(constraints.get("hotelMaxNightly"));

        return new TravelConstraintSpec(
                stringValue(safe.get("origin")), destination, dateValue(safe.get("startDate")), days, travelers,
                budget, firstNonBlank(stringValue(constraints.get("currency")), "CNY"),
                stringList(firstValue(safe.get("allowedTransportModes"), constraints.get("allowedTransportModes"))),
                stringList(firstValue(safe.get("requiredAttractionTags"), constraints.get("requiredAttractionTags"))),
                stringList(firstValue(safe.get("requiredCuisineTags"), constraints.get("requiredCuisineTags"))), hotelMax,
                constraints, mapValue(constraints.get("softPreferences")),
                intValue(safe.get("_supplementalVersion"), 0));
    }

    private static String match(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int intMatch(String input, Pattern pattern, int fallback) {
        String value = match(input, pattern);
        return value.isBlank() ? fallback : Integer.parseInt(value);
    }

    private static Integer optionalIntMatch(String input, Pattern pattern) {
        String value = match(input, pattern);
        if (value.isBlank()) return null;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return chineseNumber(value); }
    }

    private static Integer chineseNumber(String value) {
        Map<String, Integer> digits = Map.ofEntries(
                Map.entry("一", 1), Map.entry("二", 2), Map.entry("两", 2), Map.entry("三", 3),
                Map.entry("四", 4), Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7),
                Map.entry("八", 8), Map.entry("九", 9), Map.entry("十", 10));
        if (digits.containsKey(value)) return digits.get(value);
        if (value.startsWith("十") && value.length() == 2) return 10 + digits.getOrDefault(value.substring(1), 0);
        if (value.endsWith("十") && value.length() == 2) return digits.getOrDefault(value.substring(0, 1), 0) * 10;
        return null;
    }

    private static Long moneyMatchToCents(String input) {
        String value = match(input, BUDGET);
        return value.isBlank() ? null : Math.round(Double.parseDouble(value) * 100D);
    }

    private static Long moneyToCents(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        if (value instanceof Number number) return Math.round(number.doubleValue() * 100D);
        try { return Math.round(Double.parseDouble(value.toString()) * 100D); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static LocalDate dateValue(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try { return LocalDate.parse(value.toString()); }
        catch (DateTimeParseException ignored) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) return list.stream().filter(Objects::nonNull)
                .map(Object::toString).filter(item -> !item.isBlank()).toList();
        if (value == null || value.toString().isBlank()) return List.of();
        return List.of(value.toString().split("[,，]"));
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String stringValue(Object value) { return value == null ? "" : value.toString().trim(); }
    private static Object firstValue(Object first, Object second) { return first == null ? second : first; }
    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}
