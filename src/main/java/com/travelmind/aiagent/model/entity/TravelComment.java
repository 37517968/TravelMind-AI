package com.travelmind.aiagent.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 旅行方案评论实体类
 */
@Data
@TableName("travel_comment")
public class TravelComment implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 旅行方案ID
     */
    @TableField("planId")
    private Long planId;

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
     * 评论内容
     */
    private String content;

    /**
     * 父评论ID（用于回复功能）
     */
    @TableField("parentId")
    private Long parentId;

    /**
     * 被回复用户ID
     */
    @TableField("replyUserId")
    private Long replyUserId;

    /**
     * 被回复用户昵称（非数据库字段，关联查询）
     */
    @TableField(exist = false)
    private String replyUserName;

    /**
     * 点赞数
     */
    @TableField("likeCount")
    private Integer likeCount;

    /**
     * 状态：0-正常 1-已删除
     */
    private Integer status;

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
}
