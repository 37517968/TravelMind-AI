package com.travelmind.aiagent.tool.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tool_audit_log")
public class ToolAuditLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private String userId;
    private String workflowNode;
    private String toolName;
    private String toolSource;
    private String riskLevel;
    private String argumentsHash;
    private Boolean success;
    private Boolean cacheHit;
    private Boolean degraded;
    private Integer attemptCount;
    private Long durationMs;
    private String errorCode;
    private BigDecimal estimatedCost;
    private LocalDateTime createdAt;
}
