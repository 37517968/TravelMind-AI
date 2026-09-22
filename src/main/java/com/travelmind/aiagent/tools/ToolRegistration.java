package com.travelmind.aiagent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.tool.callback.GovernedToolCallback;
import com.travelmind.aiagent.tool.service.ToolGateway;
import com.travelmind.aiagent.tool.service.ToolPolicyResolver;
import com.travelmind.aiagent.tool.mcp.RemoteMcpClientManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * 集中的工具注册类
 * 注册所有旅游出行相关的工具
 */
@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Value("${amap.api-key:}")
    private String amapApiKey;

    @Value("${qweather.api-key:}")
    private String qweatherApiKey;

    @Value("${travel.tools.high-risk-enabled:false}")
    private boolean highRiskEnabled;

    @Bean
    public WeatherTool weatherTool() {
        return new WeatherTool(qweatherApiKey);
    }

    @Bean
    public POISearchTool poiSearchTool() {
        return new POISearchTool(amapApiKey);
    }

    @Bean
    public RoutePlanningTool routePlanningTool() {
        return new RoutePlanningTool(amapApiKey);
    }

    @Bean("localGovernedTools")
    public ToolCallback[] allTools(WeatherTool weatherTool, POISearchTool poiSearchTool,
                                   RoutePlanningTool routePlanningTool, ToolGateway gateway,
                                   ToolPolicyResolver policyResolver, ObjectMapper objectMapper,
                                   RemoteMcpClientManager mcpManager, Environment environment) {
        WebSearchTool webSearchTool = new WebSearchTool(searchApiKey);
        boolean allowHighRisk = highRiskEnabled && !environment.acceptsProfiles(Profiles.of("prod"));
        ToolCallback[] raw = allowHighRisk
                ? ToolCallbacks.from(webSearchTool, new WebScrapingTool(), new ResourceDownloadTool(),
                        weatherTool, poiSearchTool, routePlanningTool)
                : ToolCallbacks.from(webSearchTool, weatherTool, poiSearchTool, routePlanningTool);
        java.util.stream.Stream<ToolCallback> callbacks = java.util.stream.Stream.concat(
                java.util.Arrays.stream(raw), java.util.Arrays.stream(mcpManager.callbacks()));
        return callbacks
                .map(callback -> new GovernedToolCallback(callback,
                        policyResolver.resolve(callback.getToolDefinition().name(),
                                callback.getToolDefinition().name().contains("_") ? "MCP" : "LOCAL"), gateway, objectMapper))
                .toArray(ToolCallback[]::new);
    }
}
