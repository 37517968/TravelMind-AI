package com.travelmind.aiagent.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.task.model.AgentWorkflowCheckpoint;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AgentWorkflowCheckpointMapper extends BaseMapper<AgentWorkflowCheckpoint> {
    @Select("SELECT * FROM agent_workflow_checkpoint WHERE task_id=#{taskId} ORDER BY id")
    List<AgentWorkflowCheckpoint> selectByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM agent_workflow_checkpoint WHERE task_id=#{taskId} AND node_id=#{nodeId} " +
            "ORDER BY attempt DESC LIMIT 1")
    AgentWorkflowCheckpoint selectLatest(@Param("taskId") Long taskId, @Param("nodeId") String nodeId);
}
