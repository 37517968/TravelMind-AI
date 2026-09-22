package com.travelmind.aiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.model.entity.TravelComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 旅行方案评论 Mapper 接口
 */
@Mapper
public interface TravelCommentMapper extends BaseMapper<TravelComment> {

    /**
     * 获取方案的一级评论（分页）
     */
    @Select("SELECT c.*, u.nickname as user_name, u.avatar as user_avatar " +
            "FROM travel_comment c " +
            "LEFT JOIN user u ON c.userId = u.id " +
            "WHERE c.planId = #{planId} AND c.isDelete = 0 AND c.parentId IS NULL " +
            "ORDER BY c.createTime DESC LIMIT #{offset}, #{size}")
    List<TravelComment> selectTopLevelComments(@Param("planId") Long planId,
                                                @Param("offset") int offset,
                                                @Param("size") int size);

    /**
     * 获取评论的回复列表
     */
    @Select("SELECT c.*, u.nickname as user_name, u.avatar as user_avatar, " +
            "ru.nickname as reply_user_name " +
            "FROM travel_comment c " +
            "LEFT JOIN user u ON c.userId = u.id " +
            "LEFT JOIN user ru ON c.replyUserId = ru.id " +
            "WHERE c.parentId = #{parentId} AND c.isDelete = 0 " +
            "ORDER BY c.createTime ASC")
    List<TravelComment> selectReplies(@Param("parentId") Long parentId);

    /**
     * 统计方案的评论数
     */
    @Select("SELECT COUNT(*) FROM travel_comment WHERE planId = #{planId} AND isDelete = 0")
    long countByPlanId(@Param("planId") Long planId);

    /**
     * 增加点赞数
     */
    @Update("UPDATE travel_comment SET likeCount = likeCount + 1 WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id);

    /**
     * 减少点赞数
     */
    @Update("UPDATE travel_comment SET likeCount = likeCount - 1 WHERE id = #{id} AND likeCount > 0")
    int decrementLikeCount(@Param("id") Long id);

    /**
     * 获取方案的所有评论（用于知识库）
     */
    @Select("SELECT c.*, u.nickname as user_name " +
            "FROM travel_comment c " +
            "LEFT JOIN user u ON c.userId = u.id " +
            "WHERE c.planId = #{planId} AND c.isDelete = 0 " +
            "ORDER BY c.createTime ASC")
    List<TravelComment> selectAllByPlanId(@Param("planId") Long planId);
}


