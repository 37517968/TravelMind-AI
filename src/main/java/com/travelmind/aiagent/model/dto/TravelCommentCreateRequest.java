package com.travelmind.aiagent.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建评论请求
 */
@Data
public class TravelCommentCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 旅行方案ID
     */
    private Long planId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 父评论ID（用于回复功能，可选）
     */
    private Long parentId;

    /**
     * 被回复用户ID（可选）
     */
    private Long replyUserId;
}

