package com.travelmind.aiagent.tool.service;

import com.travelmind.aiagent.tool.model.ToolPolicy;
import com.travelmind.aiagent.tool.model.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

@Component
public class ToolPolicyResolver {
    private static final Set<String> GENERAL = Set.of("CHAT", "WEATHER_QUERY", "POI_QUERY", "ROUTE_OPTIMIZATION");

    public ToolPolicy resolve(String name, String source) {
        if (name == null) throw new IllegalArgumentException("tool name is required");
        if (name.startsWith("getWeather" ) || name.equals("getCurrentWeather"))
            return read(name, source, Duration.ofMinutes(10), Duration.ofSeconds(6), 2, 0.001);
        if (name.startsWith("search"))
            return read(name, source, Duration.ofMinutes(15), Duration.ofSeconds(6), 2, 0.002);
        if (name.startsWith("plan"))
            return read(name, source, Duration.ofMinutes(10), Duration.ofSeconds(8), 2, 0.002);
        if (name.equals("scrapeWebPage"))
            return new ToolPolicy(name, source, ToolRiskLevel.HIGH_RISK, true, true,
                    Duration.ofMinutes(5), Duration.ofSeconds(5), 1, 4000, Set.of("CHAT"), 0);
        if (name.equals("downloadResource"))
            return new ToolPolicy(name, source, ToolRiskLevel.HIGH_RISK, false, false,
                    Duration.ZERO, Duration.ofSeconds(8), 1, 1000, Set.of(), 0);
        if ("MCP".equals(source))
            return new ToolPolicy(name, source, ToolRiskLevel.COSTED, true, true,
                    Duration.ofMinutes(5), Duration.ofSeconds(10), 2, 6000, GENERAL, 0.005);
        return read(name, source, Duration.ofMinutes(5), Duration.ofSeconds(6), 1, 0);
    }

    private ToolPolicy read(String name, String source, Duration ttl, Duration timeout, int attempts, double cost) {
        return new ToolPolicy(name, source, ToolRiskLevel.READ_ONLY, true, true, ttl, timeout,
                attempts, 6000, GENERAL, cost);
    }
}
