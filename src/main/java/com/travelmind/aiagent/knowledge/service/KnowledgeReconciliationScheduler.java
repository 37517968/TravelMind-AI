package com.travelmind.aiagent.knowledge.service;

import com.travelmind.aiagent.knowledge.mapper.KnowledgeIndexStateMapper;
import com.travelmind.aiagent.knowledge.model.KnowledgeIndexState;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.service.TravelPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.runtime", name = "knowledge-worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class KnowledgeReconciliationScheduler {
    private final TravelPlanService planService;
    private final KnowledgeIndexStateMapper stateMapper;
    private final KnowledgeEventService eventService;
    private final KnowledgeIndexAdminService adminService;

    /** 缺失、失败或版本落后的索引源会重新进入同一个 Outbox/MQ 幂等流水线。 */
    @Scheduled(fixedDelayString = "${travel.knowledge.reconcile-delay-ms:900000}")
    public void reconcileAndRepair() {
        int repaired = 0;
        for (TravelPlan plan : planService.getAllPublishedPlans()) {
            long version = plan.getKnowledgeVersion() == null ? 1L : plan.getKnowledgeVersion();
            KnowledgeIndexState state = stateMapper.selectBySource("TRAVEL_PLAN", plan.getId());
            if (state == null || !"INDEXED".equals(state.getIndexStatus())
                    || Boolean.TRUE.equals(state.getIsDeleted())
                    || state.getContentVersion() == null || state.getContentVersion() < version) {
                eventService.planUpsert(plan.getId(), version);
                repaired++;
            }
        }
        var result = adminService.reconcile();
        if (repaired > 0 || !Boolean.TRUE.equals(result.get("countMatched"))) {
            log.warn("Knowledge reconciliation found drift: repaired={}, summary={}", repaired, result);
        }
    }
}
