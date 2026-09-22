package com.travelmind.aiagent.task.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_task")
public class AgentTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private Long userId;
    private String conversationId;
    private String taskType;
    private String status;
    private String workflowVersion;
    private String currentNode;
    private String requestJson;
    private String resultJson;
    private String errorCode;
    private String errorMessage;
    private Boolean cancelRequested;
    private Integer modelCallsUsed;
    private Integer tokensUsed;
    private Integer nodeExecutionsUsed;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
