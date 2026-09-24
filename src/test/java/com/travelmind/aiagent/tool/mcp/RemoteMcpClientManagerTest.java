package com.travelmind.aiagent.tool.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteMcpClientManagerTest {

    @Test
    void shouldRequireHttpsTokenAndExplicitAllowlist() {
        RemoteMcpProperties.Server insecureUrl = validServer();
        insecureUrl.setUrl("http://mcp.example.com");
        assertThatThrownBy(() -> RemoteMcpClientManager.validateServerConfiguration("travel", insecureUrl))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");

        RemoteMcpProperties.Server missingToken = validServer();
        missingToken.setToken(" ");
        assertThatThrownBy(() -> RemoteMcpClientManager.validateServerConfiguration("travel", missingToken))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("url/token");

        RemoteMcpProperties.Server emptyAllowlist = validServer();
        emptyAllowlist.setAllowedTools(Set.of());
        assertThatThrownBy(() -> RemoteMcpClientManager.validateServerConfiguration("travel", emptyAllowlist))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowed-tools");
    }

    @Test
    void shouldAcceptCompleteProductionConfiguration() {
        RemoteMcpClientManager.validateServerConfiguration("travel-provider", validServer());
    }

    @Test
    void shouldRestoreServerToolNameFromSdkWrappedCallbackName() {
        List<String> published = List.of("maps_text_search", "maps_direction_bicycling");
        assertThat(RemoteMcpClientManager.originalToolName(published, "maps_text_search"))
                .isEqualTo("maps_text_search");
        assertThat(RemoteMcpClientManager.originalToolName(published, "JavaSDKMCPClient_maps_direction_bicycling"))
                .isEqualTo("maps_direction_bicycling");
        assertThatThrownBy(() -> RemoteMcpClientManager.originalToolName(published, "maps_unknown"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Cannot resolve MCP tool name");
    }

    private RemoteMcpProperties.Server validServer() {
        RemoteMcpProperties.Server server = new RemoteMcpProperties.Server();
        server.setUrl("https://mcp.example.com");
        server.setSseEndpoint("/sse");
        server.setToken("secret");
        server.setExpectedServerVersion("1.2.0");
        server.setSchemaVersion("v1");
        server.setAllowedTools(Set.of("weather", "route_plan"));
        server.setTimeout(Duration.ofSeconds(5));
        return server;
    }

    @Test
    void shouldAcceptAmapStreamableHttpQueryAuthentication() {
        RemoteMcpProperties.Server server = validServer();
        server.setTransport(RemoteMcpProperties.Transport.STREAMABLE_HTTP);
        server.setUrl("https://mcp.amap.com");
        server.setEndpoint("/mcp");
        server.setAuthQueryParameter("key");
        server.setAuthHeader("");
        server.setExpectedServerVersion("");
        server.setAllowedTools(Set.of("maps_text_search", "maps_direction_driving"));
        RemoteMcpClientManager.validateServerConfiguration("amap", server);
    }
}
