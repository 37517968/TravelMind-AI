package com.travelmind.aiagent.tool.mcp;

import com.travelmind.aiagent.tool.service.McpSchemaRegistryService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@EnableConfigurationProperties(RemoteMcpProperties.class)
@Slf4j
public class RemoteMcpClientManager {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private final RemoteMcpProperties properties;
    private final McpSchemaRegistryService schemas;
    private final List<McpSyncClient> clients = new ArrayList<>();
    private final List<ToolCallback> callbacks = new ArrayList<>();

    public RemoteMcpClientManager(RemoteMcpProperties properties, McpSchemaRegistryService schemas) {
        this.properties = properties;
        this.schemas = schemas;
    }

    @PostConstruct
    public void initialize() {
        if (!properties.isEnabled()) {
            log.info("Remote MCP disabled: travel.mcp.remote.enabled=false, real-time tools are not registered");
            return;
        }
        if (properties.getServers().isEmpty()) {
            log.warn("Remote MCP enabled but no servers are configured");
            return;
        }
        properties.getServers().forEach(this::connect);
        if (callbacks.isEmpty()) {
            log.warn("Remote MCP enabled but no tools registered: check AMAP_MCP_API_KEY and outbound access to mcp.amap.com");
        }
    }

    public ToolCallback[] callbacks() { return callbacks.toArray(ToolCallback[]::new); }

    private void connect(String name, RemoteMcpProperties.Server config) {
        McpSyncClient client = null;
        try {
            validateServerConfiguration(name, config);
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .header("X-MCP-Schema-Version", config.getSchemaVersion());
            if (blank(config.getAuthQueryParameter())) {
                request.header(config.getAuthHeader(), authValue(config));
            }
            McpClientTransport transport = createTransport(config, request);
            client = McpClient.sync(transport).requestTimeout(config.getTimeout()).build();
            var initialized = client.initialize();
            String actualVersion = initialized.serverInfo().version();
            if (!blank(config.getExpectedServerVersion()) && !config.getExpectedServerVersion().equals(actualVersion))
                throw new IllegalStateException("MCP server version mismatch: expected="
                        + config.getExpectedServerVersion() + ", actual=" + actualVersion);
            ToolCallback[] discovered = new SyncMcpToolCallbackProvider((ignoredClient, tool) ->
                    config.getAllowedTools().contains(tool.name()), client).getToolCallbacks();
            List<String> publishedNames = client.listTools().tools().stream()
                    .map(McpSchema.Tool::name).toList();
            List<ToolCallback> serverCallbacks = new ArrayList<>();
            for (ToolCallback callback : discovered) {
                String originalName = originalToolName(publishedNames, callback.getToolDefinition().name());
                schemas.register(name, actualVersion, originalName, config.getSchemaVersion(),
                        callback.getToolDefinition().inputSchema());
                ToolCallback prefixed = new PrefixedToolCallback(name + "_" + originalName, callback);
                serverCallbacks.add(prefixed);
            }
            clients.add(client);
            callbacks.addAll(serverCallbacks);
            log.info("Remote MCP connected: server={}, version={}, allowedTools={}", name, actualVersion, discovered.length);
        } catch (Exception failure) {
            if (client != null) try { client.closeGracefully(); } catch (Exception ignored) { }
            log.warn("Remote MCP unavailable: server={}, reason={}", name, safeFailure(config, failure));
            if (config.isRequired()) throw new IllegalStateException("Required MCP server unavailable: " + name, failure);
        }
    }

    /**
     * Spring AI 会在回调名上再加一层非服务端原始名的前缀，注册和治理都需要还原成 tools/list 的原始名。
     * 以服务端返回的名字为准，避免依赖 SDK 内部命名实现。
     */
    static String originalToolName(List<String> publishedNames, String callbackName) {
        if (publishedNames.contains(callbackName)) return callbackName;
        List<String> matches = publishedNames.stream()
                .filter(raw -> callbackName.endsWith("_" + raw))
                .toList();
        if (matches.size() != 1)
            throw new IllegalStateException("Cannot resolve MCP tool name \"" + callbackName
                    + "\" against server tools " + publishedNames);
        return matches.get(0);
    }

    private McpClientTransport createTransport(RemoteMcpProperties.Server config, HttpRequest.Builder request) {
        if (config.getTransport() == RemoteMcpProperties.Transport.STREAMABLE_HTTP) {
            return HttpClientStreamableHttpTransport.builder(config.getUrl())
                    .endpoint(authenticatedEndpoint(config.getEndpoint(), config))
                    .requestBuilder(request)
                    .connectTimeout(config.getTimeout())
                    .build();
        }
        return HttpClientSseClientTransport.builder(config.getUrl())
                .sseEndpoint(authenticatedEndpoint(config.getSseEndpoint(), config))
                .requestBuilder(request)
                .build();
    }

    private String authenticatedEndpoint(String endpoint, RemoteMcpProperties.Server config) {
        if (blank(config.getAuthQueryParameter())) return endpoint;
        String separator = endpoint.contains("?") ? "&" : "?";
        return endpoint + separator + URLEncoder.encode(config.getAuthQueryParameter(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(config.getToken(), StandardCharsets.UTF_8);
    }

    static void validateServerConfiguration(String name, RemoteMcpProperties.Server config) {
        if (config == null) throw new IllegalArgumentException("remote MCP server config is required");
        if (blankStatic(name) || !SAFE_NAME.matcher(name).matches())
            throw new IllegalArgumentException("remote MCP server name is invalid");
        if (blankStatic(config.getUrl()) || blankStatic(config.getToken()))
            throw new IllegalArgumentException("remote MCP url/token is required");
        URI uri;
        try {
            uri = URI.create(config.getUrl());
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException("remote MCP url is invalid", invalidUri);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || blankStatic(uri.getHost()) || uri.getUserInfo() != null)
            throw new IllegalArgumentException("remote MCP url must be an HTTPS origin without user-info");
        if (config.getTransport() == null)
            throw new IllegalArgumentException("remote MCP transport is required");
        if (blankStatic(config.getAuthQueryParameter()) && blankStatic(config.getAuthHeader()))
            throw new IllegalArgumentException("remote MCP auth-header is required");
        if (!blankStatic(config.getAuthQueryParameter()) && !SAFE_NAME.matcher(config.getAuthQueryParameter()).matches())
            throw new IllegalArgumentException("remote MCP auth-query-parameter is invalid");
        if (blankStatic(config.getSchemaVersion()))
            throw new IllegalArgumentException("remote MCP schema-version is required");
        if (config.getAllowedTools() == null || config.getAllowedTools().isEmpty()
                || config.getAllowedTools().stream().anyMatch(tool -> blankStatic(tool) || !SAFE_NAME.matcher(tool).matches()))
            throw new IllegalArgumentException("remote MCP allowed-tools must contain explicit valid tool names");
        if (config.getTimeout() == null || config.getTimeout().isZero() || config.getTimeout().isNegative())
            throw new IllegalArgumentException("remote MCP timeout must be positive");
        String endpoint = config.getTransport() == RemoteMcpProperties.Transport.STREAMABLE_HTTP
                ? config.getEndpoint() : config.getSseEndpoint();
        if (blankStatic(endpoint) || !endpoint.startsWith("/"))
            throw new IllegalArgumentException("remote MCP endpoint must be an absolute path");
    }

    private String authValue(RemoteMcpProperties.Server config) {
        return blank(config.getAuthScheme()) ? config.getToken() : config.getAuthScheme() + " " + config.getToken();
    }

    private String safeFailure(RemoteMcpProperties.Server config, Exception failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return blank(config.getToken()) ? message : message.replace(config.getToken(), "[REDACTED]");
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean blankStatic(String value) { return value == null || value.isBlank(); }

    @PreDestroy
    public void close() { clients.forEach(client -> { try { client.closeGracefully(); } catch (Exception ignored) { } }); }

    private record PrefixedToolCallback(String name, ToolCallback delegate) implements ToolCallback {
        @Override public ToolDefinition getToolDefinition() {
            ToolDefinition original = delegate.getToolDefinition();
            return ToolDefinition.builder().name(name).description(original.description())
                    .inputSchema(original.inputSchema()).build();
        }
        @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
        @Override public String call(String input) { return delegate.call(input); }
        @Override public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
            return delegate.call(input, context);
        }
    }
}
