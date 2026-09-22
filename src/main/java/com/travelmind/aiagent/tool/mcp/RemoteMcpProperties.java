package com.travelmind.aiagent.tool.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "travel.mcp.remote")
public class RemoteMcpProperties {
    private boolean enabled;
    private Map<String, Server> servers = new LinkedHashMap<>();

    @Data
    public static class Server {
        private String url;
        private String sseEndpoint = "/sse";
        private String authHeader = "Authorization";
        private String authScheme = "Bearer";
        private String token;
        private Duration timeout = Duration.ofSeconds(10);
        private boolean required;
        private String expectedServerVersion;
        private String schemaVersion = "v1";
        private Set<String> allowedTools = new LinkedHashSet<>();
    }
}
