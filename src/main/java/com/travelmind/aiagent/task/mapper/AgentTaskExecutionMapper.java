package com.travelmind.aiagent.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.task.model.AgentTaskExecution;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentTaskExecutionMapper extends BaseMapper<AgentTaskExecution> {
    @Select("SELECT * FROM agent_task_execution WHERE task_id=#{taskId} ORDER BY id")
    List<AgentTaskExecution> selectByTaskId(@Param("taskId") Long taskId);

    @Update("UPDATE agent_task_execution SET status=#{status}, finished_at=NOW(3), duration_ms=#{durationMs}, " +
            "error_type=#{errorType}, error_message=#{errorMessage} WHERE id=#{id}")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("durationMs") long durationMs,
               @Param("errorType") String errorType, @Param("errorMessage") String errorMessage);
}
