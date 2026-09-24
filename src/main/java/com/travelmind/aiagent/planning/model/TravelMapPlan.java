package com.travelmind.aiagent.planning.model;

import java.util.List;

/** 前端地图只消费该稳定协议，不直接依赖高德 MCP 的原始响应结构。 */
public record TravelMapPlan(
        String destination,
        boolean available,
        List<DayRoute> days,
        List<String> warnings) {

    public TravelMapPlan {
        destination = destination == null ? "" : destination;
        days = ImmutableValues.list(days);
        warnings = ImmutableValues.list(warnings);
    }

    public record DayRoute(int day, List<PoiStop> stops, List<RouteLeg> legs) {
        public DayRoute {
            stops = ImmutableValues.list(stops);
            legs = ImmutableValues.list(legs);
        }
    }

    public record PoiStop(
            int order,
            String poiId,
            String name,
            String address,
            String category,
            GeoPoint location,
            List<Photo> photos) {
        public PoiStop {
            poiId = poiId == null ? "" : poiId;
            name = name == null ? "" : name;
            address = address == null ? "" : address;
            category = category == null ? "" : category;
            photos = ImmutableValues.list(photos);
        }
    }

    public record RouteLeg(
            String fromPoiId,
            String toPoiId,
            String mode,
            long distanceMeters,
            long durationSeconds,
            List<GeoPoint> polyline,
            List<String> instructions) {
        public RouteLeg {
            fromPoiId = fromPoiId == null ? "" : fromPoiId;
            toPoiId = toPoiId == null ? "" : toPoiId;
            mode = mode == null ? "" : mode;
            polyline = ImmutableValues.list(polyline);
            instructions = ImmutableValues.list(instructions);
        }
    }

    public record GeoPoint(double lng, double lat) { }

    public record Photo(String title, String url) {
        public Photo {
            title = title == null ? "" : title;
            url = url == null ? "" : url;
        }
    }

    public static TravelMapPlan unavailable(String destination, String warning) {
        return new TravelMapPlan(destination, false, List.of(),
                warning == null || warning.isBlank() ? List.of() : List.of(warning));
    }
}
