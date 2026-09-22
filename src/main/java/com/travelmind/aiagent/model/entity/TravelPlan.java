package com.travelmind.aiagent.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 旅行方案实体类
 * 用户可以分享自己制定的旅行计划到社区
 */
@Data
@TableName("travel_plan")
public class TravelPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("userId")
    private Long userId;

    /**
     * 用户昵称（非数据库字段，关联查询）
     */
    @TableField(exist = false)
    private String userName;

    /**
     * 用户头像（非数据库字段，关联查询）
     */
    @TableField(exist = false)
    private String userAvatar;

    /**
     * 方案标题
     */
    private String title;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 行程天数
     */
    private Integer days;

    /**
     * 预算（元）
     */
    private Integer budget;

    /**
     * 出行人数
     */
    private Integer travelers;

    /**
     * 旅行类型：亲子游/情侣游/朋友游/独自游/家庭游
     */
    @TableField("travelType")
    private String travelType;

    /**
     * 方案详情（Markdown格式）
     */
    private String content;

    /**
     * 方案摘要
     */
    private String summary;

    /**
     * 封面图片URL
     */
    @TableField("coverImage")
    private String coverImage;

    /**
     * 标签（逗号分隔）
     */
    private String tags;

    /**
     * 点赞数
     */
    @TableField("likeCount")
    private Integer likeCount;

    /**
     * 收藏数
     */
    @TableField("favoriteCount")
    private Integer favoriteCount;

    /**
     * 评论数
     */
    @TableField("commentCount")
    private Integer commentCount;

    /**
     * 浏览数
     */
    @TableField("viewCount")
    private Integer viewCount;

    /**
     * 状态：0-草稿 1-已发布 2-已下架
     */
    private Integer status;

    /**
     * 是否已加入知识库：0-否 1-是
     */
    @TableField("inKnowledgeBase")
    private Integer inKnowledgeBase;

    /** 单调递增的知识索引内容版本。 */
    private Long knowledgeVersion;

    /**
     * 创建时间
     */
    @TableField("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    @TableField("isDelete")
    private Integer isDelete;

    // 状态常量
    public static final int STATUS_DRAFT = 0;
    public static final int STATUS_PUBLISHED = 1;
    public static final int STATUS_OFFLINE = 2;
}
