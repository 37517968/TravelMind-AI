package com.travelmind.aiagent.tool.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mcp_tool_schema")
public class McpToolSchema {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String serverName;
    private String serverVersion;
    private String toolName;
    private String schemaVersion;
    private String schemaHash;
    private String inputSchema;
    private String status;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
