package com.travelmind.aiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.model.entity.UserFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户收藏记录 Mapper 接口
 */
@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {

    /**
     * 检查用户是否已收藏
     */
    @Select("SELECT COUNT(*) FROM user_favorite WHERE user_id = #{userId} AND plan_id = #{planId}")
    int checkUserFavorited(@Param("userId") Long userId, @Param("planId") Long planId);
}


