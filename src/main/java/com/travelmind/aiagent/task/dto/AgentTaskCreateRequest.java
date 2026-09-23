package com.travelmind.aiagent.task.dto;

import com.travelmind.aiagent.task.model.AgentTaskType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentTaskCreateRequest {
    private Long userId;
    @NotBlank
    private String conversationId;
    private AgentTaskType taskType = AgentTaskType.PLAN;
    /** 修改已有行程时的基线任务；未传时后端会在同一会话中查找最近成功的规划任务。 */
    private Long baseTaskId;
    @NotBlank
    private String prompt;
    private String destination;
    private LocalDate startDate;
    @Min(1) @Max(30)
    private Integer days;
    @Min(0)
    private Integer budget;
    @Min(1) @Max(30)
    private Integer travelers;
    private String travelType;
    @Min(1) @Max(10)
    private Integer maxModelCalls = 8;
    @Min(1000) @Max(100000)
    private Integer maxTokens = 12000;
    @Min(5) @Max(100)
    private Integer maxNodeExecutions = 32;
    @Min(2) @Max(20)
    private Integer maxAgentSteps = 8;
    private Map<String, Object> constraints = new LinkedHashMap<>();
}
