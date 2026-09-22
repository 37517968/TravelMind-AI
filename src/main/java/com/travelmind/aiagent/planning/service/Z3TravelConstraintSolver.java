package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelSolverResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.time.Duration;
import java.util.Map;

/**
 * 可选 Z3/SMT 适配器。服务关闭、超时或协议异常时降级到 JVM 内置确定性求解器。
 */
@Component
@Primary
public class Z3TravelConstraintSolver implements TravelConstraintSolver {
    private final RestClient client;
    private final DeterministicTravelConstraintSolver fallback;
    private final boolean enabled;

    public Z3TravelConstraintSolver(RestClient.Builder builder,
                                    DeterministicTravelConstraintSolver fallback,
                                    @Value("${travel.solver.z3.base-url:http://localhost:8091}") String baseUrl,
                                    @Value("${travel.solver.z3.enabled:false}") boolean enabled) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.fallback = fallback;
        this.enabled = enabled;
    }

    @Override
    public TravelSolverResult solve(TravelConstraintSpec constraints, TravelCandidateSet candidates) {
        if (!enabled) return fallback.solve(constraints, candidates);
        try {
            TravelSolverResult result = client.post().uri("/solve")
                    .body(Map.of("constraints", constraints, "candidates", candidates,
                            "timeoutMillis", Duration.ofSeconds(3).toMillis()))
                    .retrieve().body(TravelSolverResult.class);
            return result == null ? fallback.solve(constraints, candidates) : result;
        } catch (RuntimeException unavailable) {
            TravelSolverResult local = fallback.solve(constraints, candidates);
            return new TravelSolverResult(local.status(), local.selected(), local.totalCostCents(),
                    local.unsatCore(), local.relaxationSuggestions(), local.objectiveScore(),
                    Map.of("solver", "FINITE_DOMAIN_JAVA_FALLBACK", "z3Error", unavailable.getClass().getSimpleName()));
        }
    }
}
