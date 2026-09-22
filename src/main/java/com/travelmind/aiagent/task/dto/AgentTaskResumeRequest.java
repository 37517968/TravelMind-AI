package com.travelmind.aiagent.task.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class AgentTaskResumeRequest {
    @NotNull
    private Map<String, Object> supplemental;
}
