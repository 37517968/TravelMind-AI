package com.travelmind.aiagent.tool.mcp;

import com.travelmind.aiagent.tool.service.McpSchemaRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 按需执行（AMAP_MCP_LIVE_TEST=true）：校验 application.yml 的高德白名单与服务端真实工具名一致，
 * 并完成一次真实 POI 检索，避免白名单写错时只在生产静默降级。
 */
@EnabledIfEnvironmentVariable(named = "AMAP_MCP_LIVE_TEST", matches = "true")
class AmapMcpLiveConnectivityTest {

    @Test
    void shouldRegisterEveryAllowlistedToolAndSearchPois() {
        RemoteMcpProperties properties = loadProperties();
        RemoteMcpProperties.Server amap = properties.getServers().get("amap");
        assertThat(amap).as("travel.mcp.remote.servers.amap").isNotNull();
        assertThat(amap.getToken()).as("AMAP_MCP_API_KEY").isNotBlank();

        RemoteMcpClientManager manager =
                new RemoteMcpClientManager(properties, mock(McpSchemaRegistryService.class));
        try {
            manager.initialize();
            Set<String> registered = Arrays.stream(manager.callbacks())
                    .map(callback -> callback.getToolDefinition().name())
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> expected = amap.getAllowedTools().stream()
                    .map(tool -> "amap_" + tool).collect(Collectors.toCollection(TreeSet::new));
            assertThat(registered).isEqualTo(expected);

            ToolCallback textSearch = Arrays.stream(manager.callbacks())
                    .filter(callback -> "amap_maps_text_search".equals(callback.getToolDefinition().name()))
                    .findFirst().orElseThrow();
            assertThat(textSearch.call("{\"keywords\":\"西湖\",\"city\":\"杭州\",\"citylimit\":true}"))
                    .contains("西湖");
        } finally {
            manager.close();
        }
    }

    private RemoteMcpProperties loadProperties() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("amap-live-test", new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to load application.yml", failure);
        }
        ConfigurationPropertySources.attach(environment);
        RemoteMcpProperties properties = new Binder(ConfigurationPropertySources.get(environment),
                new PropertySourcesPlaceholdersResolver(environment))
                .bind("travel.mcp.remote", RemoteMcpProperties.class)
                .orElseThrow(() -> new IllegalStateException("travel.mcp.remote is missing"));
        properties.setEnabled(true);
        return properties;
    }
}
