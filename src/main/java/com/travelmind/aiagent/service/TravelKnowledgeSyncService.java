package com.travelmind.aiagent.service;

import com.travelmind.aiagent.knowledge.service.KnowledgeEventService;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.service.TravelPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 旅行社区知识库同步服务
 * 
 * 功能：
 * 1. 将用户分享的旅行方案同步到向量知识库
 * 2. 将方案的评论内容也加入知识库（作为补充信息）
 * 3. 支持定时自动同步和手动触发同步
 * 
 * 这样 AI 在回答用户问题时，可以参考社区中其他用户的真实旅行经验
 */
@Service
@Slf4j
public class TravelKnowledgeSyncService {

    @Autowired
    private TravelPlanService travelPlanService;

    @Autowired
    private KnowledgeEventService knowledgeEventService;

    /**
     * 同步所有未入库的旅行方案到知识库
     * 
     * @return 同步的方案数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int syncPlansToKnowledgeBase() {
        java.util.List<TravelPlan> plans = travelPlanService.getAllPublishedPlans();
        for (TravelPlan plan : plans) {
            knowledgeEventService.planUpsert(plan.getId(), plan.getKnowledgeVersion());
        }
        log.info("已将 {} 个旅行方案写入知识索引 Outbox", plans.size());
        return plans.size();
    }

    /**
     * 手动触发同步（可通过 API 调用）
     */
    public Map<String, Object> manualSync() {
        Map<String, Object> result = new HashMap<>();
        try {
            int count = syncPlansToKnowledgeBase();
            result.put("success", true);
            result.put("syncedCount", count);
            result.put("message", "同步成功，共同步 " + count + " 个方案");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "同步失败：" + e.getMessage());
            log.error("手动同步失败", e);
        }
        return result;
    }

    /**
     * 同步单个方案到知识库
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean syncSinglePlan(Long planId) {
        TravelPlan plan = travelPlanService.getPlanById(planId);
        if (plan == null) {
            return false;
        }
        knowledgeEventService.planUpsert(plan.getId(), plan.getKnowledgeVersion());
        return true;
    }
}

