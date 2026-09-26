package com.travelmind.aiagent.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.task.model.AgentTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentTaskMapper extends BaseMapper<AgentTask> {
    @Select("SELECT * FROM agent_task WHERE request_id = #{requestId} LIMIT 1")
    AgentTask selectByRequestId(@Param("requestId") String requestId);

    @Select("SELECT * FROM agent_task WHERE conversation_id=#{conversationId} AND user_id=#{userId} " +
            "AND status='SUCCEEDED' AND task_type IN ('PLAN','MODIFY') " +
            "AND JSON_VALID(result_json)=1 " +
            "AND JSON_UNQUOTE(JSON_EXTRACT(result_json, '$.responseType')) IN ('PLAN','MODIFY') " +
            "ORDER BY finished_at DESC, id DESC LIMIT 1")
    AgentTask selectLatestSucceededPlan(@Param("userId") Long userId, @Param("conversationId") String conversationId);

    @Update("UPDATE agent_task SET status='RUNNING', started_at=COALESCE(started_at, NOW(3)), " +
            "updated_at=NOW(3), version=version+1 WHERE id=#{id} AND status='QUEUED' AND cancel_requested=0")
    int claimQueuedTask(@Param("id") Long id);

    @Update("UPDATE agent_task SET cancel_requested=1, updated_at=NOW(3), version=version+1 " +
            "WHERE id=#{id} AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')")
    int requestCancellation(@Param("id") Long id);

    @Update("UPDATE agent_task SET status='CANCELLED', finished_at=NOW(3), updated_at=NOW(3), version=version+1 " +
            "WHERE id=#{id} AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')")
    int markCancelled(@Param("id") Long id);

    @Update("UPDATE agent_task SET status='QUEUED', error_code=NULL, error_message=NULL, cancel_requested=0, " +
            "updated_at=NOW(3), version=version+1 WHERE id=#{id} AND status IN ('WAITING_USER','FAILED')")
    int requeue(@Param("id") Long id);

    @Update("UPDATE agent_task SET current_node=#{nodeId}, node_executions_used=node_executions_used+1, " +
            "updated_at=NOW(3), version=version+1 WHERE id=#{taskId} AND status='RUNNING'")
    int enterNode(@Param("taskId") Long taskId, @Param("nodeId") String nodeId);

    @Update("UPDATE agent_task SET status='WAITING_USER', current_node=#{nodeId}, error_code='MISSING_REQUIRED_INPUT', " +
            "error_message=#{message}, updated_at=NOW(3), version=version+1 WHERE id=#{taskId} AND status='RUNNING'")
    int markWaiting(@Param("taskId") Long taskId, @Param("nodeId") String nodeId, @Param("message") String message);

    @Update("UPDATE agent_task SET status='SUCCEEDED', result_json=#{resultJson}, error_code=NULL, error_message=NULL, " +
            "finished_at=NOW(3), updated_at=NOW(3), version=version+1 WHERE id=#{taskId} AND status='RUNNING'")
    int markSucceeded(@Param("taskId") Long taskId, @Param("resultJson") String resultJson);

    @Update("UPDATE agent_task SET status='FAILED', error_code=#{errorCode}, error_message=#{message}, " +
            "finished_at=NOW(3), updated_at=NOW(3), version=version+1 WHERE id=#{taskId} AND status='RUNNING'")
    int markFailed(@Param("taskId") Long taskId, @Param("errorCode") String errorCode, @Param("message") String message);

    @Update("UPDATE agent_task SET model_calls_used=model_calls_used+#{modelCalls}, tokens_used=tokens_used+#{tokens}, " +
            "updated_at=NOW(3), version=version+1 WHERE id=#{taskId} AND status='RUNNING'")
    int addUsage(@Param("taskId") Long taskId, @Param("modelCalls") int modelCalls, @Param("tokens") int tokens);

    @Select("SELECT id FROM agent_task WHERE status='RUNNING' AND updated_at < DATE_SUB(NOW(3), INTERVAL #{seconds} SECOND) LIMIT #{limit}")
    List<Long> selectStaleRunningIds(@Param("seconds") int seconds, @Param("limit") int limit);

    @Update("UPDATE agent_task SET status='QUEUED', error_code='WORKER_LEASE_EXPIRED', " +
            "error_message='Worker lease expired; task queued for checkpoint recovery', updated_at=NOW(3), version=version+1 " +
            "WHERE id=#{id} AND status='RUNNING' AND updated_at < DATE_SUB(NOW(3), INTERVAL #{seconds} SECOND)")
    int recoverStaleRunning(@Param("id") Long id, @Param("seconds") int seconds);

    @Update("UPDATE agent_task SET status='WAITING_USER', updated_at=NOW(3), version=version+1 " +
            "WHERE id=#{id} AND status IN ('QUEUED','RUNNING')")
    int markPaused(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM agent_task WHERE status=#{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT COALESCE(SUM(tokens_used),0) FROM agent_task WHERE created_at>=DATE_SUB(NOW(3), INTERVAL 24 HOUR)")
    long sumTokensLast24Hours();

    @Select("SELECT COALESCE(SUM(model_calls_used),0) FROM agent_task WHERE created_at>=DATE_SUB(NOW(3), INTERVAL 24 HOUR)")
    long sumModelCallsLast24Hours();
}
