package com.travelmind.aiagent.task.dto;

import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AgentTaskView {
    AgentTask task;
    List<AgentWorkflowCheckpoint> checkpoints;
}
