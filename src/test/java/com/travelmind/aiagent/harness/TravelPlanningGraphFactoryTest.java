package com.travelmind.aiagent.harness;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TravelPlanningGraphFactoryTest {
    @Test
    void satRequestShouldFollowFixedFormalPlanningRoute() {
        List<String> visited = new ArrayList<>();
        var graph = new TravelPlanningGraphFactory().compile(node -> {
            visited.add(node);
            return switch (node) {
                case TravelPlanningGraphFactory.CHECK -> Map.of("route", "CONTINUE");
                case TravelPlanningGraphFactory.SOLVE -> Map.of("route", "SAT");
                case TravelPlanningGraphFactory.VALIDATE -> Map.of("route", "VALID");
                case TravelPlanningGraphFactory.FRESHNESS -> Map.of("route", "FRESH");
                default -> Map.of("route", "CONTINUE");
            };
        });

        graph.invoke(Map.of(), RunnableConfig.builder().threadId("sat-case").build());

        assertThat(visited).containsExactly(
                TravelPlanningGraphFactory.EXTRACT, TravelPlanningGraphFactory.CHECK,
                TravelPlanningGraphFactory.CONTEXT, TravelPlanningGraphFactory.CANDIDATES,
                TravelPlanningGraphFactory.SOLVE, TravelPlanningGraphFactory.GENERATE,
                TravelPlanningGraphFactory.VALIDATE, TravelPlanningGraphFactory.FRESHNESS,
                TravelPlanningGraphFactory.PERSIST);
    }

    @Test
    void unsatRequestShouldRouteToRelaxationAndStopBeforeGeneration() {
        List<String> visited = new ArrayList<>();
        var graph = new TravelPlanningGraphFactory().compile(node -> {
            visited.add(node);
            if (node.equals(TravelPlanningGraphFactory.CHECK)) return Map.of("route", "CONTINUE");
            if (node.equals(TravelPlanningGraphFactory.SOLVE)) return Map.of("route", "UNSAT");
            return Map.of("route", "CONTINUE");
        });

        graph.invoke(Map.of(), RunnableConfig.builder().threadId("unsat-case").build());

        assertThat(visited).contains(TravelPlanningGraphFactory.RELAX)
                .doesNotContain(TravelPlanningGraphFactory.GENERATE, TravelPlanningGraphFactory.PERSIST);
    }
}
