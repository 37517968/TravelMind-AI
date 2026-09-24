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
        private Transport transport = Transport.SSE;
        private String url;
        private String endpoint = "/mcp";
        private String sseEndpoint = "/sse";
        private String authHeader = "Authorization";
        private String authScheme = "Bearer";
        /** 非空时把 token 放入 endpoint 查询参数，而不是 HTTP Header。 */
        private String authQueryParameter;
        private String token;
        private Duration timeout = Duration.ofSeconds(10);
        private boolean required;
        private String expectedServerVersion;
        private String schemaVersion = "v1";
        private Set<String> allowedTools = new LinkedHashSet<>();
    }

    public enum Transport {
        SSE,
        STREAMABLE_HTTP
    }
}
