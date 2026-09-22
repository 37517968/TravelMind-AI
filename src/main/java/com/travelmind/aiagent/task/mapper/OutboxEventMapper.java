package com.travelmind.aiagent.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.task.model.OutboxEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {
    @Select("SELECT * FROM outbox_event WHERE status IN ('PENDING','FAILED') AND next_retry_at<=NOW(3) " +
            "ORDER BY id LIMIT #{limit}")
    List<OutboxEvent> selectPublishable(@Param("limit") int limit);

    @Update("UPDATE outbox_event SET status='PUBLISHED', published_at=NOW(3), last_error=NULL WHERE id=#{id} AND status<>'PUBLISHED'")
    int markPublished(@Param("id") Long id);

    @Update("UPDATE outbox_event SET status='FAILED', retry_count=retry_count+1, last_error=#{error}, " +
            "next_retry_at=DATE_ADD(NOW(3), INTERVAL LEAST(300, POW(2, LEAST(retry_count, 8))) SECOND) WHERE id=#{id}")
    int markFailed(@Param("id") Long id, @Param("error") String error);

    @Select("SELECT COUNT(*) FROM outbox_event WHERE status IN ('PENDING','FAILED')")
    long countBacklog();
}
