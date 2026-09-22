package com.travelmind.aiagent.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.tool.model.ToolResult;
import com.travelmind.aiagent.tool.service.TravelToolFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 ToolGateway 的统一信封转换为求解器候选集。价格估算会显式标记，不能冒充实时库存价。
 */
@Component
public class TravelCandidateCollector {
    private final ObjectProvider<TravelToolFacade> tools;
    private final ObjectMapper objectMapper;

    public TravelCandidateCollector(ObjectProvider<TravelToolFacade> tools, ObjectMapper objectMapper) {
        this.tools = tools;
        this.objectMapper = objectMapper;
    }

    public TravelCandidateSet collect(Long taskId, String userId, TravelConstraintSpec spec) {
        TravelCandidateSet supplied = suppliedCandidates(spec.hardConstraints());
        if (supplied != null) return supplied;

        Instant now = Instant.now();
        TravelToolFacade facade = tools.getIfAvailable();
        ToolResult hotels = invoke(facade, "searchHotels", Map.of("city", spec.destination(), "keyword", ""), taskId, userId);
        ToolResult attractions = invoke(facade, "searchAttractions",
                Map.of("city", spec.destination(), "keyword", String.join(" ", spec.requiredAttractionTags())), taskId, userId);
        ToolResult restaurants = invoke(facade, "searchRestaurants",
                Map.of("city", spec.destination(), "cuisineType", String.join(" ", spec.requiredCuisineTags())), taskId, userId);

        List<TravelCandidate> transportList = spec.origin().isBlank() ? List.of() : List.of(candidate(
                TravelCandidate.CandidateType.TRANSPORT, spec.origin() + "至" + spec.destination(), 30000,
                Math.max(spec.travelers(), 4), List.of(), now, now.plus(15, ChronoUnit.MINUTES), "ROUTE_ESTIMATE",
                Map.of("mode", defaultMode(spec), "priceConfidence", "ESTIMATED")));
        List<TravelCandidate> hotelList = toolCandidates(hotels, TravelCandidate.CandidateType.HOTEL,
                spec.destination() + "住宿候选", 40000, List.of("住宿"), spec.travelers(), now);
        List<TravelCandidate> attractionList = toolCandidates(attractions, TravelCandidate.CandidateType.ATTRACTION,
                spec.destination() + "景点候选", 8000,
                spec.requiredAttractionTags().isEmpty() ? List.of("通用景点") : spec.requiredAttractionTags(), 0, now);
        List<TravelCandidate> restaurantList = toolCandidates(restaurants, TravelCandidate.CandidateType.RESTAURANT,
                spec.destination() + "餐厅候选", 6000,
                spec.requiredCuisineTags().isEmpty() ? List.of("本地美食") : spec.requiredCuisineTags(), 0, now);
        return new TravelCandidateSet(transportList, hotelList, attractionList, restaurantList, now);
    }

    private TravelCandidateSet suppliedCandidates(Map<String, Object> constraints) {
        Object raw = constraints.get("candidateSet");
        if (raw == null) return null;
        try { return objectMapper.convertValue(raw, TravelCandidateSet.class); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private ToolResult invoke(TravelToolFacade facade, String name, Map<String, Object> args, Long taskId, String userId) {
        if (facade == null) return unavailable(name);
        return facade.invokeTyped(name, args, "CANDIDATE_RETRIEVAL", String.valueOf(taskId), userId);
    }

    private List<TravelCandidate> toolCandidates(ToolResult result, TravelCandidate.CandidateType type,
                                                 String fallbackName, long estimatedCost, List<String> tags,
                                                 int capacity, Instant now) {
        List<TravelCandidate> values = new ArrayList<>();
        Instant observed = result.observedAt() == null ? now : result.observedAt();
        Instant expires = result.expiresAt() == null ? now.plus(15, ChronoUnit.MINUTES) : result.expiresAt();
        Map<String, Object> attributes = Map.of(
                "priceConfidence", "ESTIMATED",
                "toolSuccess", result.success(),
                "rawEvidence", String.valueOf(result.data()),
                "degraded", result.degraded());
        TravelCandidate base = candidate(type, fallbackName, estimatedCost, capacity, tags, observed, expires,
                result.source() == null ? "TOOL_GATEWAY" : result.source(), attributes);
        values.add(new TravelCandidate(base.id(), base.type(), base.name(), base.city(), base.unitCostCents(),
                base.durationMinutes(), base.capacity(), base.tags(), result.success(), base.observedAt(),
                base.expiresAt(), base.source(), base.attributes()));
        return List.copyOf(values);
    }

    private TravelCandidate candidate(TravelCandidate.CandidateType type, String name, long cost, int capacity,
                                      List<String> tags, Instant observedAt, Instant expiresAt, String source,
                                      Map<String, Object> attributes) {
        return new TravelCandidate(type.name().toLowerCase() + "-" + UUID.randomUUID(), type, name, "", cost,
                type == TravelCandidate.CandidateType.HOTEL ? 24 * 60 : 120, capacity, tags, true,
                observedAt, expiresAt, source, attributes);
    }

    private String defaultMode(TravelConstraintSpec spec) {
        return spec.allowedTransportModes().isEmpty() ? "PUBLIC_TRANSIT" : spec.allowedTransportModes().getFirst();
    }

    private ToolResult unavailable(String source) {
        Instant now = Instant.now();
        return new ToolResult(false, "工具不可用，使用显式估算候选", source, now,
                now.plus(5, ChronoUnit.MINUTES), "TOOLS_UNAVAILABLE", true, true, false, List.of());
    }
}
