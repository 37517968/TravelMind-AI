package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TravelRouteSelectionServiceTest {
    private final TravelRouteSelectionService service = new TravelRouteSelectionService();

    @Test
    void destinationOnlyShouldPauseWithSeveralRouteOptions() {
        var decision = service.decide(spec(List.of()), candidates(), Map.of("prompt", "去杭州玩三天"));

        assertThat(decision.waitingForSelection()).isTrue();
        assertThat(decision.options()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(decision.options()).allSatisfy(option -> assertThat(option.attractions()).isNotEmpty());
    }

    @Test
    void namedAttractionsShouldSkipSelectionAndNarrowCandidates() {
        var decision = service.decide(spec(List.of("灵隐寺", "西湖")), candidates(),
                Map.of("prompt", "去杭州的灵隐寺和西湖玩"));

        assertThat(decision.waitingForSelection()).isFalse();
        assertThat(decision.candidates().attractions()).extracting(TravelCandidate::name)
                .containsExactlyInAnyOrder("灵隐寺", "西湖");
    }

    @Test
    void selectedRouteShouldResumeWithOnlyItsPoiDomain() {
        var decision = service.decide(spec(List.of()), candidates(), Map.of(
                "selectedRouteId", "route-2", "selectedAttractionIds", List.of("west-lake", "lingyin")));

        assertThat(decision.waitingForSelection()).isFalse();
        assertThat(decision.selectedRouteId()).isEqualTo("route-2");
        assertThat(decision.candidates().attractions()).extracting(TravelCandidate::id)
                .containsExactlyInAnyOrder("west-lake", "lingyin");
    }

    private TravelConstraintSpec spec(List<String> specific) {
        return new TravelConstraintSpec("", "杭州", null, 3, 1, 300_000L, "CNY", List.of(),
                List.of(), specific, List.of(), null, Map.of(), Map.of(), 0);
    }

    private TravelCandidateSet candidates() {
        Instant now = Instant.now();
        List<TravelCandidate> attractions = List.of(
                attraction("west-lake", "西湖", now), attraction("lingyin", "灵隐寺", now),
                attraction("hef坊", "河坊街", now), attraction("xixi", "西溪湿地", now),
                attraction("leifeng", "雷峰塔", now), attraction("liangzhu", "良渚博物院", now));
        return new TravelCandidateSet(List.of(), List.of(), attractions, List.of(), now);
    }

    private TravelCandidate attraction(String id, String name, Instant now) {
        return new TravelCandidate(id, TravelCandidate.CandidateType.ATTRACTION, name, "杭州", 0, 120, 0,
                List.of("景点"), true, now, now.plusSeconds(600), "AMAP_MCP",
                Map.of("address", "杭州市", "location", Map.of("lng", 120.1, "lat", 30.2)));
    }
}
