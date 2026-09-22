package com.travelmind.aiagent.knowledge.service;

import com.travelmind.aiagent.service.TravelKnowledgeSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只在 Knowledge Worker 中执行全量兜底同步，避免每个 API/Worker 副本重复扫描。 */
@Component
@ConditionalOnProperty(prefix = "app.runtime", name = "knowledge-worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class KnowledgeSyncScheduler {
    private final TravelKnowledgeSyncService syncService;

    @Scheduled(fixedDelayString = "${travel.knowledge.full-sync-delay-ms:3600000}")
    public void sync() {
        try {
            int count = syncService.syncPlansToKnowledgeBase();
            log.info("Knowledge fallback synchronization queued {} plans", count);
        } catch (RuntimeException failure) {
            log.error("Knowledge fallback synchronization failed", failure);
        }
    }
}
