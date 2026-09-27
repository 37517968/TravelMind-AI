package com.travelmind.aiagent.task.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** Lightweight projection for polling and capacity probes; excludes request/result JSON and checkpoints. */
@Data
public class AgentTaskStatusView {
    private Long taskId;
    private String status;
    private String currentNode;
    private Integer modelCallsUsed;
    private Integer tokensUsed;
    private Integer nodeExecutionsUsed;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
