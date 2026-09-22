package com.travelmind.aiagent.task.service;

import com.travelmind.aiagent.task.mapper.AgentTaskMapper;
import com.travelmind.aiagent.observability.PlatformObservability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.runtime", name = "agent-worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class StaleAgentTaskRecovery {
    private final AgentTaskMapper taskMapper;
    private final AgentTaskService taskService;
    private final PlatformObservability observability;

    @Value("${agent.task.worker-lease-seconds:300}")
    private int workerLeaseSeconds;

    @Scheduled(fixedDelayString = "${agent.task.recovery-scan-delay-ms:30000}")
    public void recover() {
        for (Long taskId : taskMapper.selectStaleRunningIds(workerLeaseSeconds, 50)) {
            if (taskService.recoverStale(taskId, workerLeaseSeconds)) {
                observability.recoveredTask();
                log.warn("Recovered stale agent task {} from its latest checkpoint", taskId);
            }
        }
    }
}
