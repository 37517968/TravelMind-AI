package com.travelmind.aiagent.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建旅行方案请求
 */
@Data
public class TravelPlanCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

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
     * 旅行类型
     */
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
    private String coverImage;

    /**
     * 标签（逗号分隔）
     */
    private String tags;
}

