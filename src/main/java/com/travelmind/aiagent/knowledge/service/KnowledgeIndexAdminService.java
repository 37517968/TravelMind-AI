package com.travelmind.aiagent.knowledge.service;

import com.travelmind.aiagent.knowledge.index.LexicalKnowledgeIndex;
import com.travelmind.aiagent.knowledge.index.SemanticKnowledgeIndex;
import com.travelmind.aiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.travelmind.aiagent.knowledge.mapper.KnowledgeIndexStateMapper;
import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.mapper.TravelPlanMapper;
import com.travelmind.aiagent.model.entity.TravelPlan;
import com.travelmind.aiagent.service.TravelPlanService;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class KnowledgeIndexAdminService {
    private final TravelPlanService planService;
    private final TravelPlanMapper planMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeIndexStateMapper stateMapper;
    private final KnowledgeChunkFactory chunkFactory;
    private final KnowledgeStateStore stateStore;
    private final LexicalKnowledgeIndex lexicalIndex;
    private final SemanticKnowledgeIndex semanticIndex;
    private final RedissonClient redissonClient;

    public Map<String, Object> reconcile() {
        long mysqlChunks = chunkMapper.countActive();
        long elasticChunks;
        String elasticError = null;
        try { elasticChunks = lexicalIndex.count(); }
        catch (RuntimeException error) { elasticChunks = -1; elasticError = error.getMessage(); }
        List<com.travelmind.aiagent.knowledge.model.KnowledgeIndexState> inconsistent = stateMapper.selectInconsistent(100);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mysqlActiveChunks", mysqlChunks);
        result.put("elasticsearchActiveChunks", elasticChunks);
        result.put("countMatched", elasticChunks >= 0 && mysqlChunks == elasticChunks);
        result.put("inconsistentSources", inconsistent);
        result.put("elasticsearchAvailable", lexicalIndex.available());
        if (elasticError != null) result.put("elasticsearchError", elasticError);
        return result;
    }

    public Map<String, Object> rebuildBlueGreen() {
        RLock lock = redissonClient.getLock("lock:knowledge:index:rebuild");
        try {
            if (!lock.tryLock(0, 30, java.util.concurrent.TimeUnit.MINUTES)) {
                return Map.of("accepted", false, "reason", "已有其他实例正在执行知识索引重建");
            }
            return doRebuildBlueGreen();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待知识索引重建锁时被中断", interrupted);
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private Map<String, Object> doRebuildBlueGreen() {
        lexicalIndex.ensureActiveIndex();
        String generation = lexicalIndex.activeAlias() + "-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        lexicalIndex.createIndex(generation);
        // 向量库没有别名能力。重建前按事实表清理旧 source，防止已删除方案留下幽灵向量。
        for (Long sourceId : chunkMapper.selectDistinctSourceIds("TRAVEL_PLAN")) {
            semanticIndex.deleteBySource("TRAVEL_PLAN", sourceId);
        }
        chunkMapper.tombstoneAllActive();
        List<TravelPlan> plans = planService.getAllPublishedPlans();
        int chunkCount = 0;
        for (TravelPlan plan : plans) {
            List<KnowledgeChunk> chunks = chunkFactory.fromPlan(plan);
            lexicalIndex.upsertTo(generation, chunks);
            semanticIndex.upsert(chunks);
            stateStore.indexed("rebuild-" + generation, "TRAVEL_PLAN", plan.getId(),
                    plan.getKnowledgeVersion() == null ? 1L : plan.getKnowledgeVersion(),
                    chunkFactory.documentHash(chunks), chunks);
            planMapper.markAsInKnowledgeBase(plan.getId());
            chunkCount += chunks.size();
        }
        lexicalIndex.switchAlias(generation);
        return Map.of("generation", generation, "documents", plans.size(), "chunks", chunkCount,
                "alias", lexicalIndex.activeAlias());
    }
}
