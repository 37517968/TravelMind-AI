package com.travelmind.aiagent.task.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_workflow_checkpoint")
public class AgentWorkflowCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String nodeId;
    private String nodeVersion;
    private Integer attempt;
    private String nodeStatus;
    private String inputSnapshot;
    private String outputSnapshot;
    private String stateSnapshot;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorType;
    private String errorMessage;
    private Boolean retryable;
    private Long durationMs;
}
