package com.travelmind.aiagent.planning.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.planning.model.TravelMapPlan;
import com.travelmind.aiagent.tool.model.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 容忍 MCP content/text 包装以及高德不同版本的字段层级，并收敛成内部 POI/路线结构。 */
final class AmapPayloadParser {
    private final ObjectMapper mapper;

    AmapPayloadParser(ObjectMapper mapper) { this.mapper = mapper; }

    List<Poi> pois(ToolResult result) {
        if (result == null || !result.success()) return List.of();
        JsonNode root = json(result.data());
        List<JsonNode> nodes = new ArrayList<>();
        collectArrays(root, "pois", nodes);
        // 详情接口有些版本直接返回单个 POI 对象。
        if (nodes.isEmpty() && looksLikePoi(root)) nodes.add(root);
        Map<String, Poi> unique = new LinkedHashMap<>();
        for (JsonNode node : nodes) {
            if (node.isArray()) node.forEach(item -> addPoi(unique, item));
            else addPoi(unique, node);
        }
        return List.copyOf(unique.values());
    }

    Optional<RouteData> route(ToolResult result) {
        if (result == null || !result.success()) return Optional.empty();
        JsonNode root = json(result.data());
        long distance = firstLong(root, "distance");
        long duration = firstLong(root, "duration");
        List<TravelMapPlan.GeoPoint> polyline = new ArrayList<>();
        List<String> instructions = new ArrayList<>();
        collectTextFields(root, "polyline", value -> parsePolyline(value, polyline));
        collectTextFields(root, "instruction", value -> {
            if (!value.isBlank() && instructions.size() < 30) instructions.add(value);
        });
        if (polyline.isEmpty() && distance == 0 && duration == 0) return Optional.empty();
        return Optional.of(new RouteData(distance, duration, List.copyOf(polyline), List.copyOf(instructions)));
    }

    private void addPoi(Map<String, Poi> unique, JsonNode node) {
        if (node == null || !node.isObject()) return;
        String id = text(node, "id", "poiid", "poiId");
        String name = text(node, "name");
        TravelMapPlan.GeoPoint location = location(node.get("location"));
        if (name.isBlank() || location == null) return;
        if (id.isBlank()) id = name + "@" + location.lng() + "," + location.lat();
        List<TravelMapPlan.Photo> photos = new ArrayList<>();
        JsonNode photoNodes = node.get("photos");
        if (photoNodes != null) {
            if (photoNodes.isArray()) photoNodes.forEach(photo -> addPhoto(photos, photo));
            else addPhoto(photos, photoNodes);
        }
        Poi incoming = new Poi(id, name, text(node, "address"), text(node, "city", "cityname"),
                text(node, "type", "typecode"), location, List.copyOf(photos));
        unique.merge(id, incoming, Poi::merge);
    }

    private void addPhoto(List<TravelMapPlan.Photo> photos, JsonNode node) {
        if (node == null || !node.isObject() || photos.size() >= 3) return;
        String url = text(node, "url");
        if (url.startsWith("https://") || url.startsWith("http://"))
            photos.add(new TravelMapPlan.Photo(text(node, "title"), url));
    }

    private JsonNode json(Object raw) {
        if (raw == null) return mapper.createObjectNode();
        try {
            JsonNode node = raw instanceof String text ? mapper.readTree(text) : mapper.valueToTree(raw);
            for (int i = 0; i < 4; i++) {
                if (node != null && node.isTextual()) node = mapper.readTree(node.asText());
                else break;
            }
            JsonNode embedded = findEmbeddedJson(node);
            return embedded == null ? node : embedded;
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    private JsonNode findEmbeddedJson(JsonNode node) {
        if (node == null) return null;
        if (node.isObject() && (node.has("pois") || node.has("route") || node.has("paths") || node.has("transits")))
            return node;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if ("text".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    try {
                        JsonNode parsed = mapper.readTree(entry.getValue().asText());
                        JsonNode found = findEmbeddedJson(parsed);
                        if (found != null) return found;
                    } catch (Exception ignored) { }
                }
                JsonNode found = findEmbeddedJson(entry.getValue());
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findEmbeddedJson(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void collectArrays(JsonNode node, String field, List<JsonNode> values) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (field.equals(entry.getKey())) values.add(entry.getValue());
                else collectArrays(entry.getValue(), field, values);
            });
        } else if (node.isArray()) node.forEach(child -> collectArrays(child, field, values));
    }

    private void collectTextFields(JsonNode node, String field, java.util.function.Consumer<String> consumer) {
        if (node == null) return;
        if (node.isObject()) node.fields().forEachRemaining(entry -> {
            if (field.equals(entry.getKey()) && entry.getValue().isValueNode()) consumer.accept(entry.getValue().asText());
            else collectTextFields(entry.getValue(), field, consumer);
        });
        else if (node.isArray()) node.forEach(child -> collectTextFields(child, field, consumer));
    }

    private long firstLong(JsonNode node, String field) {
        if (node == null) return 0;
        if (node.isObject()) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode()) {
                try { return Math.round(Double.parseDouble(value.asText())); }
                catch (NumberFormatException ignored) { }
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                long found = firstLong(fields.next().getValue(), field);
                if (found > 0) return found;
            }
        } else if (node.isArray()) for (JsonNode child : node) {
            long found = firstLong(child, field);
            if (found > 0) return found;
        }
        return 0;
    }

    private void parsePolyline(String raw, List<TravelMapPlan.GeoPoint> target) {
        if (raw == null) return;
        for (String pair : raw.split(";")) {
            TravelMapPlan.GeoPoint point = location(mapper.getNodeFactory().textNode(pair));
            if (point != null && (target.isEmpty() || !target.getLast().equals(point))) target.add(point);
        }
    }

    private TravelMapPlan.GeoPoint location(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            JsonNode lng = node.has("lng") ? node.get("lng") : node.get("longitude");
            JsonNode lat = node.has("lat") ? node.get("lat") : node.get("latitude");
            if (lng != null && lat != null) return point(lng.asText(), lat.asText());
        }
        String[] pair = node.asText("").split(",");
        return pair.length == 2 ? point(pair[0], pair[1]) : null;
    }

    private TravelMapPlan.GeoPoint point(String lng, String lat) {
        try {
            double x = Double.parseDouble(lng.trim()), y = Double.parseDouble(lat.trim());
            return x >= -180 && x <= 180 && y >= -90 && y <= 90 ? new TravelMapPlan.GeoPoint(x, y) : null;
        } catch (Exception ignored) { return null; }
    }

    private boolean looksLikePoi(JsonNode node) {
        return node != null && node.isObject() && node.has("name") && node.has("location");
    }

    private String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node == null ? null : node.get(name);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText();
        }
        return "";
    }

    record Poi(String id, String name, String address, String city, String type,
               TravelMapPlan.GeoPoint location, List<TravelMapPlan.Photo> photos) {
        Poi merge(Poi other) {
            return new Poi(id, name.isBlank() ? other.name : name,
                    address.isBlank() ? other.address : address, city.isBlank() ? other.city : city,
                    type.isBlank() ? other.type : type, location == null ? other.location : location,
                    photos.isEmpty() ? other.photos : photos);
        }
    }

    record RouteData(long distanceMeters, long durationSeconds, List<TravelMapPlan.GeoPoint> polyline,
                     List<String> instructions) { }
}
