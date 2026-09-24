package com.travelmind.aiagent.planning.model;

import java.util.List;

/** 在完整求解前展示给用户选择的景点路线草案。 */
public record TravelRouteOption(
        String id,
        String destination,
        String title,
        String emoji,
        String summary,
        List<RouteAttraction> attractions) {

    public TravelRouteOption {
        attractions = attractions == null ? List.of() : List.copyOf(attractions);
    }

    public record RouteAttraction(String id, String name, String address, String photoUrl) {}
}
