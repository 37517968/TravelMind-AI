package com.travelmind.aiagent.tool.service;

import com.travelmind.aiagent.tool.mapper.McpToolSchemaMapper;
import com.travelmind.aiagent.tool.model.McpToolSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class McpSchemaRegistryService {
    private final McpToolSchemaMapper mapper;

    public void register(String server, String serverVersion, String tool, String schemaVersion, String inputSchema) {
        String hash = sha256(inputSchema);
        McpToolSchema existing = mapper.selectOneVersion(server, tool, schemaVersion);
        if (existing != null && !hash.equals(existing.getSchemaHash())) {
            throw new IllegalStateException("MCP Schema drift detected for " + server + "/" + tool
                    + "; increment schema-version before deployment");
        }
        if (existing == null) {
            existing = new McpToolSchema();
            existing.setServerName(server);
            existing.setServerVersion(serverVersion);
            existing.setToolName(tool);
            existing.setSchemaVersion(schemaVersion);
            existing.setSchemaHash(hash);
            existing.setInputSchema(inputSchema);
            existing.setStatus("ACTIVE");
            existing.setFirstSeenAt(LocalDateTime.now());
            existing.setLastSeenAt(LocalDateTime.now());
            mapper.insert(existing);
        } else {
            existing.setServerVersion(serverVersion);
            existing.setStatus("ACTIVE");
            existing.setLastSeenAt(LocalDateTime.now());
            mapper.updateById(existing);
        }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
