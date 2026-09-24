package com.travelmind.aiagent.planning.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.tool.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AmapPayloadParserTest {
    private final AmapPayloadParser parser = new AmapPayloadParser(new ObjectMapper());

    @Test
    void shouldUnwrapMcpTextAndKeepPoiPhotos() {
        String data = """
                [{"type":"text","text":"{\\\"pois\\\":[{\\\"id\\\":\\\"p1\\\",\\\"name\\\":\\\"上海博物馆\\\",\\\"location\\\":\\\"121.475,31.228\\\",\\\"address\\\":\\\"人民大道201号\\\",\\\"photos\\\":[{\\\"title\\\":\\\"外景\\\",\\\"url\\\":\\\"https://img.example/museum.jpg\\\"}]}]}"}]
                """;

        var pois = parser.pois(success(data));

        assertThat(pois).hasSize(1);
        assertThat(pois.getFirst().id()).isEqualTo("p1");
        assertThat(pois.getFirst().location().lng()).isEqualTo(121.475);
        assertThat(pois.getFirst().photos()).extracting(photo -> photo.url())
                .containsExactly("https://img.example/museum.jpg");
    }

    @Test
    void shouldExtractRouteCostAndPolyline() {
        String data = """
                {"route":{"paths":[{"distance":"1200","cost":{"duration":"960"},"steps":[
                  {"instruction":"向东步行","polyline":"121.1,31.1;121.2,31.2"},
                  {"instruction":"到达目的地","polyline":"121.2,31.2;121.3,31.3"}
                ]}]}}
                """;

        var route = parser.route(success(data)).orElseThrow();

        assertThat(route.distanceMeters()).isEqualTo(1200);
        assertThat(route.durationSeconds()).isEqualTo(960);
        assertThat(route.polyline()).hasSize(3);
        assertThat(route.instructions()).containsExactly("向东步行", "到达目的地");
    }

    private ToolResult success(String data) {
        return new ToolResult(true, data, "MCP", Instant.now(), null,
                null, false, false, false, List.of());
    }
}
