package com.travelmind.aiagent.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.task.event.AgentProgressEventStore;
import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.tool.model.ToolResult;
import com.travelmind.aiagent.tool.service.TravelToolFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把 ToolGateway 的统一信封转换为求解器候选集。价格估算会显式标记，不能冒充实时库存价。
 */
@Component
public class TravelCandidateCollector {
    private static final String RETRIEVAL_NODE = "CANDIDATE_RETRIEVAL";
    private static final String AMAP_TEXT_SEARCH = "amap_maps_text_search";
    private static final String AMAP_AROUND_SEARCH = "amap_maps_around_search";
    private static final Map<String, String> TOOL_LABELS = Map.of(
            AMAP_TEXT_SEARCH, "高德地点", AMAP_AROUND_SEARCH, "高德周边地点");

    private final ObjectProvider<TravelToolFacade> tools;
    private final ObjectMapper objectMapper;
    private final AgentProgressEventStore eventStore;
    private final AmapPayloadParser amap;

    public TravelCandidateCollector(ObjectProvider<TravelToolFacade> tools, ObjectMapper objectMapper,
                                    AgentProgressEventStore eventStore) {
        this.tools = tools;
        this.objectMapper = objectMapper;
        this.eventStore = eventStore;
        this.amap = new AmapPayloadParser(objectMapper);
    }

    public TravelCandidateSet collect(Long taskId, String userId, TravelConstraintSpec spec) {
        TravelCandidateSet supplied = suppliedCandidates(spec.hardConstraints());
        if (supplied != null) return supplied;

        Instant now = Instant.now();
        TravelToolFacade facade = tools.getIfAvailable();
        if (spec.destination().isBlank()) return new TravelCandidateSet(List.of(), List.of(), List.of(), List.of(), now);
        ToolResult hotels = searchPoi(facade, spec.destination(), "酒店", taskId, userId);
        String attractionKeyword = spec.requiredAttractionTags().isEmpty()
                ? "景点" : String.join(" ", spec.requiredAttractionTags());
        ToolResult attractions = searchPoi(facade, spec.destination(), attractionKeyword, taskId, userId);
        ToolResult nearbyAttractions = nearbyPoi(facade, attractions, attractionKeyword, taskId, userId);
        String restaurantKeyword = spec.requiredCuisineTags().isEmpty()
                ? "美食" : String.join(" ", spec.requiredCuisineTags());
        ToolResult restaurants = searchPoi(facade, spec.destination(), restaurantKeyword, taskId, userId);

        List<TravelCandidate> transportList = spec.origin().isBlank() ? List.of() : List.of(candidate(
                TravelCandidate.CandidateType.TRANSPORT, spec.origin() + "至" + spec.destination(), 30000,
                Math.max(spec.travelers(), 4), List.of(), now, now.plus(15, ChronoUnit.MINUTES), "ROUTE_ESTIMATE",
                Map.of("mode", defaultMode(spec), "priceConfidence", "ESTIMATED")));
        List<TravelCandidate> hotelList = toolCandidates(hotels, TravelCandidate.CandidateType.HOTEL,
                spec.destination() + "住宿候选", 40000, List.of("住宿"), spec.travelers(), now);
        List<TravelCandidate> attractionList = mergeCandidates(
                toolCandidates(attractions, TravelCandidate.CandidateType.ATTRACTION,
                spec.destination() + "景点候选", 8000,
                        spec.requiredAttractionTags().isEmpty() ? List.of("通用景点") : spec.requiredAttractionTags(), 0, now),
                amap.pois(nearbyAttractions).isEmpty() ? List.of() : toolCandidates(nearbyAttractions, TravelCandidate.CandidateType.ATTRACTION,
                        spec.destination() + "周边景点候选", 8000,
                        spec.requiredAttractionTags().isEmpty() ? List.of("通用景点") : spec.requiredAttractionTags(), 0, now));
        List<TravelCandidate> restaurantList = toolCandidates(restaurants, TravelCandidate.CandidateType.RESTAURANT,
                spec.destination() + "餐厅候选", 6000,
                spec.requiredCuisineTags().isEmpty() ? List.of("本地美食") : spec.requiredCuisineTags(), 0, now);
        return new TravelCandidateSet(transportList, hotelList, attractionList, restaurantList, now);
    }

    private ToolResult searchPoi(TravelToolFacade facade, String city, String keyword, Long taskId, String userId) {
        if (facade == null || !facade.hasTool(AMAP_TEXT_SEARCH)) return unavailable(AMAP_TEXT_SEARCH);
        return invoke(facade, AMAP_TEXT_SEARCH, Map.of("keywords", keyword, "city", city), taskId, userId);
    }

    private ToolResult nearbyPoi(TravelToolFacade facade, ToolResult seed, String keyword, Long taskId, String userId) {
        if (facade == null || !facade.hasTool(AMAP_AROUND_SEARCH)) return unavailable(AMAP_AROUND_SEARCH);
        // 关键词搜索的裁剪响应可能没有坐标，取第一个带坐标的候选当周边搜索中心。
        String location = amap.pois(seed).stream()
                .map(AmapPayloadParser.Poi::location)
                .filter(point -> point != null)
                .findFirst()
                .map(point -> point.lng() + "," + point.lat()).orElse("");
        if (location.isBlank()) return unavailable(AMAP_AROUND_SEARCH);
        return invoke(facade, AMAP_AROUND_SEARCH,
                Map.of("keywords", keyword, "location", location, "radius", 5000), taskId, userId);
    }

    private TravelCandidateSet suppliedCandidates(Map<String, Object> constraints) {
        Object raw = constraints.get("candidateSet");
        if (raw == null) return null;
        try { return objectMapper.convertValue(raw, TravelCandidateSet.class); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    /** 工具调用过程通过进度事件暴露给前端，用小字展示"正在查什么、查没查到"。 */
    private ToolResult invoke(TravelToolFacade facade, String name, Map<String, Object> args, Long taskId, String userId) {
        String label = TOOL_LABELS.getOrDefault(name, name);
        if (facade == null) {
            progress(taskId, name, "DEGRADED", label + "查询能力未开启，改用参考估算");
            return unavailable(name);
        }
        progress(taskId, name, "RUNNING", "正在查询" + label + "实时信息…");
        ToolResult result = facade.invokeTyped(name, args, RETRIEVAL_NODE, String.valueOf(taskId), userId);
        progress(taskId, name, result.success() ? "SUCCEEDED" : "DEGRADED", result.success()
                ? label + "信息查询完成"
                : label + "暂时查不到，已改用参考估算");
        return result;
    }

    private void progress(Long taskId, String toolName, String status, String message) {
        if (eventStore == null || taskId == null) return;
        eventStore.publish(taskId, "TOOL", RETRIEVAL_NODE, status, message, 40,
                Map.of("key", "tool:" + toolName, "tool", toolName));
    }

    private List<TravelCandidate> toolCandidates(ToolResult result, TravelCandidate.CandidateType type,
                                                 String fallbackName, long estimatedCost, List<String> tags,
                                                 int capacity, Instant now) {
        List<TravelCandidate> values = new ArrayList<>();
        Instant observed = result.observedAt() == null ? now : result.observedAt();
        Instant expires = result.expiresAt() == null ? now.plus(15, ChronoUnit.MINUTES) : result.expiresAt();
        List<AmapPayloadParser.Poi> pois = amap.pois(result);
        for (AmapPayloadParser.Poi poi : pois) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("priceConfidence", "ESTIMATED");
            attributes.put("toolSuccess", true);
            attributes.put("poiId", poi.id());
            attributes.put("address", poi.address());
            attributes.put("poiType", poi.type());
            attributes.put("location", poi.location());
            attributes.put("photos", poi.photos());
            values.add(new TravelCandidate(poi.id(), type, poi.name(), poi.city(), estimatedCost,
                    type == TravelCandidate.CandidateType.HOTEL ? 24 * 60 : 120,
                    capacity, tags, true, observed, expires,
                    result.source() == null ? "AMAP_MCP" : result.source(), attributes));
        }
        if (!values.isEmpty()) return List.copyOf(values);
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

    @SafeVarargs
    private final List<TravelCandidate> mergeCandidates(List<TravelCandidate>... groups) {
        Map<String, TravelCandidate> unique = new LinkedHashMap<>();
        for (List<TravelCandidate> group : groups)
            for (TravelCandidate candidate : group) unique.putIfAbsent(candidate.id(), candidate);
        return List.copyOf(unique.values());
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
