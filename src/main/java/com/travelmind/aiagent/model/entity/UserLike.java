package com.travelmind.aiagent.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户点赞记录实体类
 */
@Data
@TableName("user_like")
public class UserLike implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 目标ID（方案ID或评论ID）
     */
    private Long targetId;

    /**
     * 目标类型：1-方案 2-评论
     */
    private Integer targetType;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    // 目标类型常量
    public static final int TARGET_TYPE_PLAN = 1;
    public static final int TARGET_TYPE_COMMENT = 2;
}


