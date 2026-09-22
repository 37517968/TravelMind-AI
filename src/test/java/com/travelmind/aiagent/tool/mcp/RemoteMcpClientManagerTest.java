package com.travelmind.aiagent.tool.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteMcpClientManagerTest {

    @Test
    void shouldRequireHttpsVersionAndExplicitAllowlist() {
        RemoteMcpProperties.Server insecureUrl = validServer();
        insecureUrl.setUrl("http://mcp.example.com");
        assertThatThrownBy(() -> RemoteMcpClientManager.validateServerConfiguration("travel", insecureUrl))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");

        RemoteMcpProperties.Server missingVersion = validServer();
        missingVersion.setExpectedServerVersion(" ");
        assertThatThrownBy(() -> RemoteMcpClientManager.validateServerConfiguration("travel", missingVersion))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected-server-version");

        RemoteMcpProperties.Server emptyAllowlist = validServer();
        emptyAllowlist.setAllowedTools(Set.of());
        assertThatThrownBy(() -> RemoteMcpClientManager.validateServerConfiguration("travel", emptyAllowlist))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowed-tools");
    }

    @Test
    void shouldAcceptCompleteProductionConfiguration() {
        RemoteMcpClientManager.validateServerConfiguration("travel-provider", validServer());
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
}
