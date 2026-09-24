package com.travelmind.aiagent.planning.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelMapPlan;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import com.travelmind.aiagent.task.event.AgentProgressEventStore;
import com.travelmind.aiagent.tool.model.ToolResult;
import com.travelmind.aiagent.tool.service.TravelToolFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 使用高德 MCP 补全入选 POI，并为每天相邻站点生成可直接渲染的路线。 */
@Component
public class TravelMapPlanService {
    private static final String NODE = "MAP_PLANNING";
    private static final String DETAIL = "amap_maps_search_detail";
    private static final Map<String, String> ROUTE_TOOLS = Map.of(
            "DRIVING", "amap_maps_direction_driving",
            "WALKING", "amap_maps_direction_walking",
            "BICYCLING", "amap_maps_bicycling",
            "TRANSIT", "amap_maps_direction_transit_integrated");

    private final ObjectProvider<TravelToolFacade> tools;
    private final ObjectMapper mapper;
    private final AgentProgressEventStore events;
    private final AmapPayloadParser amap;

    public TravelMapPlanService(ObjectProvider<TravelToolFacade> tools, ObjectMapper mapper,
                                AgentProgressEventStore events) {
        this.tools = tools;
        this.mapper = mapper;
        this.events = events;
        this.amap = new AmapPayloadParser(mapper);
    }

    public TravelMapPlan build(Long taskId, String userId, TravelConstraintSpec spec, TravelSolverResult solution) {
        List<String> warnings = new ArrayList<>();
        TravelToolFacade facade = tools.getIfAvailable();
        List<TravelCandidate> selectedPois = solution.selected().stream()
                .filter(item -> item.type() == TravelCandidate.CandidateType.ATTRACTION
                        || item.type() == TravelCandidate.CandidateType.RESTAURANT)
                .toList();
        if (selectedPois.isEmpty()) selectedPois = solution.selected().stream()
                .filter(item -> item.type() == TravelCandidate.CandidateType.HOTEL).toList();

        List<ResolvedPoi> pois = new ArrayList<>();
        for (TravelCandidate candidate : selectedPois) {
            ResolvedPoi poi = resolvePoi(facade, candidate, taskId, userId);
            if (poi != null) pois.add(poi);
        }
        if (pois.isEmpty()) return TravelMapPlan.unavailable(spec.destination(),
                "高德 MCP 未返回可定位的 POI，文本行程仍可正常使用");
        pois = nearestNeighborOrder(pois);

        String mode = routeMode(spec.allowedTransportModes());
        int dayCount = Math.max(1, spec.days());
        int perDay = Math.max(1, (int) Math.ceil(pois.size() / (double) dayCount));
        List<TravelMapPlan.DayRoute> days = new ArrayList<>();
        for (int day = 1; day <= dayCount; day++) {
            int from = Math.min((day - 1) * perDay, pois.size());
            int to = Math.min(from + perDay, pois.size());
            if (from >= to) break;
            List<ResolvedPoi> dayPois = pois.subList(from, to);
            List<TravelMapPlan.PoiStop> stops = new ArrayList<>();
            for (int i = 0; i < dayPois.size(); i++) stops.add(dayPois.get(i).stop(i + 1));
            List<TravelMapPlan.RouteLeg> legs = new ArrayList<>();
            for (int i = 0; i + 1 < dayPois.size(); i++) {
                ResolvedPoi origin = dayPois.get(i), destination = dayPois.get(i + 1);
                legs.add(route(facade, origin, destination, mode, spec.destination(), taskId, userId, warnings));
            }
            days.add(new TravelMapPlan.DayRoute(day, stops, legs));
        }
        return new TravelMapPlan(spec.destination(), true, days, List.copyOf(warnings));
    }

    private ResolvedPoi resolvePoi(TravelToolFacade facade, TravelCandidate candidate, Long taskId, String userId) {
        ResolvedPoi base = fromCandidate(candidate);
        String poiId = Objects.toString(candidate.attributes().getOrDefault("poiId", candidate.id()), "");
        if (facade == null || !facade.hasTool(DETAIL) || poiId.isBlank()) return base;
        progress(taskId, DETAIL, "RUNNING", "正在补全“" + candidate.name() + "”的地点详情…");
        ToolResult result = facade.invokeTyped(DETAIL, Map.of("id", poiId), NODE, String.valueOf(taskId), userId);
        ResolvedPoi detailed = amap.pois(result).stream().findFirst().map(this::fromAmap).orElse(null);
        progress(taskId, DETAIL, result.success() ? "SUCCEEDED" : "DEGRADED",
                result.success() ? "地点详情补全完成" : "地点详情暂时不可用，保留搜索结果");
        return merge(base, detailed);
    }

    private TravelMapPlan.RouteLeg route(TravelToolFacade facade, ResolvedPoi origin, ResolvedPoi destination,
                                          String mode, String city, Long taskId, String userId,
                                          List<String> warnings) {
        String tool = ROUTE_TOOLS.get(mode);
        List<TravelMapPlan.GeoPoint> fallback = List.of(origin.location, destination.location);
        if (facade == null || tool == null || !facade.hasTool(tool)) {
            warnings.add(mode + "路线工具不可用，地图仅连接景点直线");
            return new TravelMapPlan.RouteLeg(origin.id, destination.id, mode, 0, 0, fallback, List.of());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("origin", coordinate(origin.location));
        args.put("destination", coordinate(destination.location));
        if ("TRANSIT".equals(mode)) {
            args.put("city", city);
            args.put("cityd", city);
        }
        progress(taskId, tool, "RUNNING", "正在规划“" + origin.name + "”到“" + destination.name + "”的路线…");
        ToolResult result = facade.invokeTyped(tool, args, NODE, String.valueOf(taskId), userId);
        AmapPayloadParser.RouteData route = amap.route(result).orElse(null);
        progress(taskId, tool, route == null ? "DEGRADED" : "SUCCEEDED",
                route == null ? "路线详情暂时不可用，地图将连接景点位置" : "分段路线规划完成");
        if (route == null) {
            warnings.add(origin.name + "至" + destination.name + "未取得道路折线");
            return new TravelMapPlan.RouteLeg(origin.id, destination.id, mode, 0, 0, fallback, List.of());
        }
        return new TravelMapPlan.RouteLeg(origin.id, destination.id, mode, route.distanceMeters(),
                route.durationSeconds(), route.polyline().isEmpty() ? fallback : route.polyline(), route.instructions());
    }

    private ResolvedPoi fromCandidate(TravelCandidate candidate) {
        try {
            Object rawLocation = candidate.attributes().get("location");
            TravelMapPlan.GeoPoint location = rawLocation == null ? null
                    : mapper.convertValue(rawLocation, TravelMapPlan.GeoPoint.class);
            List<TravelMapPlan.Photo> photos = candidate.attributes().get("photos") == null ? List.of()
                    : mapper.convertValue(candidate.attributes().get("photos"), new TypeReference<>() { });
            if (location == null) return null;
            return new ResolvedPoi(Objects.toString(candidate.attributes().getOrDefault("poiId", candidate.id()), ""),
                    candidate.name(), Objects.toString(candidate.attributes().get("address"), ""),
                    Objects.toString(candidate.attributes().get("poiType"), candidate.type().name()), location, photos);
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private ResolvedPoi fromAmap(AmapPayloadParser.Poi poi) {
        return new ResolvedPoi(poi.id(), poi.name(), poi.address(), poi.type(), poi.location(), poi.photos());
    }

    private ResolvedPoi merge(ResolvedPoi base, ResolvedPoi detail) {
        if (base == null) return detail;
        if (detail == null) return base;
        return new ResolvedPoi(base.id.isBlank() ? detail.id : base.id,
                detail.name.isBlank() ? base.name : detail.name,
                detail.address.isBlank() ? base.address : detail.address,
                detail.category.isBlank() ? base.category : detail.category,
                detail.location == null ? base.location : detail.location,
                detail.photos.isEmpty() ? base.photos : detail.photos);
    }

    private String routeMode(List<String> modes) {
        String value = modes == null || modes.isEmpty() ? "TRANSIT" : modes.getFirst().toUpperCase(Locale.ROOT);
        if (value.contains("WALK") || value.contains("步行")) return "WALKING";
        if (value.contains("BICYCLE") || value.contains("BIKE") || value.contains("骑")) return "BICYCLING";
        if (value.contains("DRIV") || value.contains("CAR") || value.contains("驾")) return "DRIVING";
        return "TRANSIT";
    }

    /** 用经纬度做确定性的最近邻排序，避免搜索结果直接造成明显的跨城区折返。 */
    private List<ResolvedPoi> nearestNeighborOrder(List<ResolvedPoi> input) {
        if (input.size() < 3) return List.copyOf(input);
        List<ResolvedPoi> remaining = new ArrayList<>(input);
        List<ResolvedPoi> ordered = new ArrayList<>();
        ordered.add(remaining.removeFirst());
        while (!remaining.isEmpty()) {
            ResolvedPoi previous = ordered.getLast();
            int best = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                ResolvedPoi candidate = remaining.get(i);
                double dx = previous.location.lng() - candidate.location.lng();
                double dy = previous.location.lat() - candidate.location.lat();
                double distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            ordered.add(remaining.remove(best));
        }
        return List.copyOf(ordered);
    }

    private String coordinate(TravelMapPlan.GeoPoint point) { return point.lng() + "," + point.lat(); }

    private void progress(Long taskId, String tool, String status, String message) {
        if (events != null && taskId != null) events.publish(taskId, "TOOL", NODE, status, message, 65,
                Map.of("key", "tool:" + tool, "tool", tool));
    }

    private record ResolvedPoi(String id, String name, String address, String category,
                               TravelMapPlan.GeoPoint location, List<TravelMapPlan.Photo> photos) {
        TravelMapPlan.PoiStop stop(int order) {
            return new TravelMapPlan.PoiStop(order, id, name, address, category, location, photos);
        }
    }
}
