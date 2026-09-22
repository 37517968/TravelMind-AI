package com.travelmind.aiagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.model.entity.TravelPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 旅行方案 Mapper 接口
 */
@Mapper
public interface TravelPlanMapper extends BaseMapper<TravelPlan> {

    /**
     * 获取热门方案（按点赞数排序）
     */
    @Select("SELECT * FROM travel_plan WHERE isDelete = 0 AND status = 1 " +
            "ORDER BY likeCount DESC LIMIT #{limit}")
    List<TravelPlan> selectHotPlans(@Param("limit") int limit);

    /**
     * 全文搜索方案
     */
    @Select("SELECT * FROM travel_plan WHERE isDelete = 0 AND status = 1 " +
            "AND MATCH(title, content) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) " +
            "ORDER BY createTime DESC LIMIT #{offset}, #{size}")
    List<TravelPlan> searchByKeyword(@Param("keyword") String keyword, 
                                      @Param("offset") int offset, 
                                      @Param("size") int size);

    /**
     * 增加浏览量
     */
    @Update("UPDATE travel_plan SET viewCount = viewCount + 1 WHERE id = #{id}")
    int incrementViewCount(@Param("id") Long id);

    /**
     * 增加点赞数
     */
    @Update("UPDATE travel_plan SET likeCount = likeCount + 1 WHERE id = #{id}")
    int incrementLikeCount(@Param("id") Long id);

    /**
     * 减少点赞数
     */
    @Update("UPDATE travel_plan SET likeCount = likeCount - 1 WHERE id = #{id} AND likeCount > 0")
    int decrementLikeCount(@Param("id") Long id);

    /**
     * 增加收藏数
     */
    @Update("UPDATE travel_plan SET favoriteCount = favoriteCount + 1 WHERE id = #{id}")
    int incrementFavoriteCount(@Param("id") Long id);

    /**
     * 减少收藏数
     */
    @Update("UPDATE travel_plan SET favoriteCount = favoriteCount - 1 WHERE id = #{id} AND favoriteCount > 0")
    int decrementFavoriteCount(@Param("id") Long id);

    /**
     * 增加评论数
     */
    @Update("UPDATE travel_plan SET commentCount = commentCount + 1 WHERE id = #{id}")
    int incrementCommentCount(@Param("id") Long id);

    /**
     * 减少评论数
     */
    @Update("UPDATE travel_plan SET commentCount = commentCount - 1 WHERE id = #{id} AND commentCount > 0")
    int decrementCommentCount(@Param("id") Long id);

    /**
     * 标记已加入知识库
     */
    @Update("UPDATE travel_plan SET inKnowledgeBase = 1 WHERE id = #{id}")
    int markAsInKnowledgeBase(@Param("id") Long id);

    /**
     * 获取未加入知识库的方案
     */
    @Select("SELECT * FROM travel_plan WHERE isDelete = 0 AND status = 1 AND inKnowledgeBase = 0")
    List<TravelPlan> selectPlansNotInKnowledgeBase();

    @Update("UPDATE travel_plan SET knowledge_version = knowledge_version + 1, updateTime = NOW() WHERE id = #{id}")
    int incrementKnowledgeVersion(@Param("id") Long id);

    @Select("SELECT knowledge_version FROM travel_plan WHERE id = #{id}")
    Long selectKnowledgeVersion(@Param("id") Long id);

    @Select("SELECT * FROM travel_plan WHERE id = #{id} LIMIT 1")
    TravelPlan selectRawById(@Param("id") Long id);
}


