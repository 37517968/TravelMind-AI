package com.travelmind.aiagent.task.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_task_execution")
public class AgentTaskExecution {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String commandType;
    private String messageId;
    private String traceId;
    private String spanId;
    private String status;
    private String workerInstance;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorType;
    private String errorMessage;
}
