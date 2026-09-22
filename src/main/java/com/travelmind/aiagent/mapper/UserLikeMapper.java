package com.travelmind.aiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.model.entity.UserLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户点赞记录 Mapper 接口
 */
@Mapper
public interface UserLikeMapper extends BaseMapper<UserLike> {

    /**
     * 检查用户是否已点赞
     */
    @Select("SELECT COUNT(*) FROM user_like WHERE user_id = #{userId} " +
            "AND target_id = #{targetId} AND target_type = #{targetType}")
    int checkUserLiked(@Param("userId") Long userId,
                       @Param("targetId") Long targetId,
                       @Param("targetType") Integer targetType);
}


