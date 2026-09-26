package com.travelmind.aiagent.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.task.model.AgentConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
    @Select("SELECT * FROM agent_conversation WHERE user_id=#{userId} AND status='ACTIVE' ORDER BY updated_at DESC")
    List<AgentConversation> selectActiveByUser(@Param("userId") Long userId);

    @Select("SELECT * FROM agent_conversation WHERE conversation_id=#{conversationId} AND user_id=#{userId} AND status='ACTIVE' LIMIT 1")
    AgentConversation selectOwned(@Param("userId") Long userId, @Param("conversationId") String conversationId);

    @Update("UPDATE agent_conversation SET last_message_preview=#{preview}, " +
            "title=IF(title='新旅行会话', #{title}, title), updated_at=NOW(3) " +
            "WHERE conversation_id=#{conversationId} AND user_id=#{userId} AND status='ACTIVE'")
    int touch(@Param("userId") Long userId, @Param("conversationId") String conversationId,
              @Param("title") String title, @Param("preview") String preview);
}
