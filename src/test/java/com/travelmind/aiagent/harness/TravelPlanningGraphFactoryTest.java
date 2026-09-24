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
                TravelPlanningGraphFactory.INTENT,
                TravelPlanningGraphFactory.EXTRACT, TravelPlanningGraphFactory.CHECK,
                TravelPlanningGraphFactory.CONTEXT, TravelPlanningGraphFactory.CANDIDATES,
                TravelPlanningGraphFactory.ROUTE_SELECTION, TravelPlanningGraphFactory.DETAILS_CHECK,
                TravelPlanningGraphFactory.SOLVE, TravelPlanningGraphFactory.MAP, TravelPlanningGraphFactory.GENERATE,
                TravelPlanningGraphFactory.VALIDATE, TravelPlanningGraphFactory.FRESHNESS,
                TravelPlanningGraphFactory.PERSIST);
    }

    @Test
    void chitchatRequestShouldReplyAndStopBeforePlanning() {
        List<String> visited = new ArrayList<>();
        var graph = new TravelPlanningGraphFactory().compile(node -> {
            visited.add(node);
            return Map.of("route", TravelPlanningGraphFactory.INTENT.equals(node) ? "CHAT" : "CONTINUE");
        });

        graph.invoke(Map.of(), RunnableConfig.builder().threadId("chitchat-case").build());

        assertThat(visited).containsExactly(TravelPlanningGraphFactory.INTENT, TravelPlanningGraphFactory.CHAT_REPLY);
    }

    @Test
    void modificationShouldLoadBasePlanBeforeExtractingChangedConstraints() {
        List<String> visited = new ArrayList<>();
        var graph = new TravelPlanningGraphFactory().compile(node -> {
            visited.add(node);
            return switch (node) {
                case TravelPlanningGraphFactory.INTENT -> Map.of("route", "MODIFY");
                case TravelPlanningGraphFactory.BASE_PLAN, TravelPlanningGraphFactory.CHECK -> Map.of("route", "CONTINUE");
                case TravelPlanningGraphFactory.SOLVE -> Map.of("route", "UNSAT");
                default -> Map.of("route", "CONTINUE");
            };
        });

        graph.invoke(Map.of(), RunnableConfig.builder().threadId("modify-case").build());

        assertThat(visited).startsWith(TravelPlanningGraphFactory.INTENT, TravelPlanningGraphFactory.BASE_PLAN,
                TravelPlanningGraphFactory.EXTRACT).contains(TravelPlanningGraphFactory.RELAX);
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
