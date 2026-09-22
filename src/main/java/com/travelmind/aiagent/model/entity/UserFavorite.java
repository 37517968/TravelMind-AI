package com.travelmind.aiagent.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户收藏记录实体类
 */
@Data
@TableName("user_favorite")
public class UserFavorite implements Serializable {

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
     * 方案ID
     */
    private Long planId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}


