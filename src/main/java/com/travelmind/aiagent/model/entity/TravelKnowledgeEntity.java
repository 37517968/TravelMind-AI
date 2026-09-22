package com.travelmind.aiagent.model.entity;

import com.travelmind.aiagent.model.enums.TravelKnowledgeLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 旅行知识实体
 * 统一抽象城市、区县和具体旅行方案
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelKnowledgeEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 节点ID
     */
    private String id;

    /**
     * 标题
     */
    private String title;

    /**
     * 文本内容
     */
    private String content;

    /**
     * 城市
     */
    private String city;

    /**
     * 区县
     */
    private String district;

    /**
     * 层级
     */
    private TravelKnowledgeLevel level;

    /**
     * 父节点ID
     */
    private String parentId;

    /**
     * 方案ID
     */
    private Long planId;

    /**
     * 旅行类型
     */
    private String travelType;

    /**
     * 标签
     */
    private String tags;

    /**
     * 数据来源
     */
    private String source;

    /**
     * 扩展元数据
     */
    private Map<String, Object> metadata;
}
