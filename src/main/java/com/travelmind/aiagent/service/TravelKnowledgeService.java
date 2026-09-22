package com.travelmind.aiagent.service;

import com.travelmind.aiagent.rag.TravelKnowledgeIndexService;
import com.travelmind.aiagent.model.enums.TravelKnowledgeLevel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 旅游知识库服务
 * 负责管理知识库的更新和同步
 */
@Service
@Slf4j
public class TravelKnowledgeService {

    @Resource
    private TravelKnowledgeIndexService travelKnowledgeIndexService;

    @Resource
    private TravelKnowledgeSyncService travelKnowledgeSyncService;

    /**
     * 手动刷新知识库
     * 将新的用户方案和评论加入知识库
     */
    public int refreshKnowledgeBase() {
        if (travelKnowledgeIndexService == null) {
            log.warn("旅游知识索引未配置，跳过刷新");
            return 0;
        }
        int count = travelKnowledgeSyncService.syncPlansToKnowledgeBase();
        log.info("知识库刷新成功，新增 {} 个方案节点", count);
        return count;
    }

    /**
     * 添加单个文档到知识库
     */
    public boolean addDocument(Document document) {
        if (travelKnowledgeIndexService == null || document == null) {
            return false;
        }
        return travelKnowledgeIndexService.upsertEntities(List.of(
                com.travelmind.aiagent.model.entity.TravelKnowledgeEntity.builder()
                        .id("manual:" + java.util.UUID.randomUUID())
                        .title(String.valueOf(document.getMetadata().getOrDefault("title", "手动文档")))
                        .content(document.getText())
                        .city((String) document.getMetadata().get("city"))
                        .district((String) document.getMetadata().get("district"))
                        .level(TravelKnowledgeLevel.PLAN)
                        .parentId((String) document.getMetadata().get("parent_id"))
                        .source(String.valueOf(document.getMetadata().getOrDefault("source", "manual")))
                        .metadata(document.getMetadata())
                        .build()
        )) > 0;
    }

    /**
     * 搜索相关文档
     */
    public List<Document> searchDocuments(String query, int topK) {
        if (travelKnowledgeIndexService == null) {
            return List.of();
        }
        return travelKnowledgeIndexService.search(query, topK);
    }
}

