package com.travelmind.aiagent.harness;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 声明式固定路由。节点实现由 Harness 注入，因此 StateGraph 负责路由，Harness 继续负责可靠执行。
 * 入口先由 LLM 判定意图：闲聊走 CHAT_REPLY 直接回复，旅行诉求才进入正式规划链路。
 */
@Component
public class TravelPlanningGraphFactory {
    public static final String INTENT = "INTENT_ROUTING";
    public static final String CHAT_REPLY = "CHAT_REPLY";
    public static final String EXTRACT = "CONSTRAINT_EXTRACTION";
    public static final String CHECK = "CONSTRAINT_VALIDATION";
    public static final String CONTEXT = "CONTEXT_BUILDING";
    public static final String CANDIDATES = "CANDIDATE_RETRIEVAL";
    public static final String SOLVE = "CONSTRAINT_SOLVING";
    public static final String RELAX = "UNSAT_RELAXATION";
    public static final String GENERATE = "ITINERARY_GENERATION";
    public static final String VALIDATE = "DETERMINISTIC_VALIDATION";
    public static final String FRESHNESS = "FRESHNESS_RECHECK";
    public static final String PERSIST = "PERSISTING";

    public CompiledGraph compile(Function<String, Map<String, Object>> nodeRunner) {
        try {
            OverAllState graphStateTemplate = new OverAllState();
            graphStateTemplate.registerKeyAndStrategy("route", new ReplaceStrategy());
            graphStateTemplate.registerKeyAndStrategy("lastNode", new ReplaceStrategy());
            graphStateTemplate.registerKeyAndStrategy("taskId", new ReplaceStrategy());
            StateGraph graph = new StateGraph(graphStateTemplate);
            add(graph, INTENT, nodeRunner);
            add(graph, CHAT_REPLY, nodeRunner);
            add(graph, EXTRACT, nodeRunner);
            add(graph, CHECK, nodeRunner);
            add(graph, CONTEXT, nodeRunner);
            add(graph, CANDIDATES, nodeRunner);
            add(graph, SOLVE, nodeRunner);
            add(graph, RELAX, nodeRunner);
            add(graph, GENERATE, nodeRunner);
            add(graph, VALIDATE, nodeRunner);
            add(graph, FRESHNESS, nodeRunner);
            add(graph, PERSIST, nodeRunner);

            graph.addEdge(START, INTENT)
                    .addConditionalEdges(INTENT, edge_async(state -> state.value("route", "CONTINUE")), Map.of(
                            "CONTINUE", EXTRACT,
                            "CHAT", CHAT_REPLY))
                    .addEdge(EXTRACT, CHECK)
                    .addConditionalEdges(CHECK, edge_async(state -> state.value("route", "WAITING")), Map.of(
                            "CONTINUE", CONTEXT,
                            "WAITING", END))
                    .addEdge(CONTEXT, CANDIDATES)
                    .addEdge(CANDIDATES, SOLVE)
                    .addConditionalEdges(SOLVE, edge_async(state -> state.value("route", "UNKNOWN")), Map.of(
                            "SAT", GENERATE,
                            "UNSAT", RELAX,
                            "UNKNOWN", RELAX))
                    .addEdge(RELAX, END)
                    .addEdge(GENERATE, VALIDATE)
                    .addConditionalEdges(VALIDATE, edge_async(state -> state.value("route", "INVALID")), Map.of(
                            "VALID", FRESHNESS,
                            "INVALID", RELAX))
                    .addConditionalEdges(FRESHNESS, edge_async(state -> state.value("route", "STALE")), Map.of(
                            "FRESH", PERSIST,
                            "STALE", RELAX))
                    .addEdge(PERSIST, END)
                    .addEdge(CHAT_REPLY, END);
            return graph.compile();
        } catch (GraphStateException error) {
            throw new IllegalStateException("旅行规划 StateGraph 编译失败", error);
        }
    }

    private void add(StateGraph graph, String node, Function<String, Map<String, Object>> runner)
            throws GraphStateException {
        graph.addNode(node, node_async(state -> runner.apply(node)));
    }
}
