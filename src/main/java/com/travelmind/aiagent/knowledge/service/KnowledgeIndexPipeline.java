package com.travelmind.aiagent.knowledge.service;

import com.travelmind.aiagent.knowledge.index.LexicalKnowledgeIndex;
import com.travelmind.aiagent.knowledge.index.SemanticKnowledgeIndex;
import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.model.KnowledgeIndexCommand;
import com.travelmind.aiagent.knowledge.model.KnowledgeIndexState;
import com.travelmind.aiagent.mapper.TravelPlanMapper;
import com.travelmind.aiagent.model.entity.TravelPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIndexPipeline {
    private final TravelPlanMapper planMapper;
    private final KnowledgeChunkFactory chunkFactory;
    private final KnowledgeStateStore stateStore;
    private final LexicalKnowledgeIndex lexicalIndex;
    private final SemanticKnowledgeIndex semanticIndex;

    public void process(KnowledgeIndexCommand command) {
        KnowledgeIndexState current = stateStore.state(command.sourceType(), command.sourceId());
        if (alreadyApplied(current, command)) {
            log.info("Knowledge event {} already applied at version {}", command.eventId(), current.getContentVersion());
            return;
        }
        try {
            TravelPlan plan = planMapper.selectRawById(command.sourceId());
            boolean shouldDelete = "DELETE".equals(command.action()) || plan == null ||
                    Integer.valueOf(1).equals(plan.getIsDelete()) || !Integer.valueOf(TravelPlan.STATUS_PUBLISHED).equals(plan.getStatus());
            if (shouldDelete) {
                delete(command);
                return;
            }
            long actualVersion = plan.getKnowledgeVersion() == null ? command.contentVersion() : plan.getKnowledgeVersion();
            List<KnowledgeChunk> chunks = chunkFactory.fromPlan(plan);
            if (chunks.isEmpty()) throw new IllegalStateException("No indexable chunks for travel plan " + plan.getId());
            lexicalIndex.deleteBySource(command.sourceType(), command.sourceId());
            semanticIndex.deleteBySource(command.sourceType(), command.sourceId());
            lexicalIndex.upsert(chunks);
            semanticIndex.upsert(chunks);
            stateStore.indexed(command.eventId(), command.sourceType(), command.sourceId(), actualVersion,
                    chunkFactory.documentHash(chunks), chunks);
            planMapper.markAsInKnowledgeBase(plan.getId());
        } catch (RuntimeException failure) {
            stateStore.failed(command.sourceType(), command.sourceId(), command.contentVersion(), command.eventId(), failure);
            throw failure;
        }
    }

    private void delete(KnowledgeIndexCommand command) {
        lexicalIndex.deleteBySource(command.sourceType(), command.sourceId());
        semanticIndex.deleteBySource(command.sourceType(), command.sourceId());
        stateStore.deleted(command.eventId(), command.sourceType(), command.sourceId(), command.contentVersion());
    }

    private boolean alreadyApplied(KnowledgeIndexState state, KnowledgeIndexCommand command) {
        if (state == null || !"INDEXED".equals(state.getIndexStatus())) return false;
        // 同一知识源的版本只允许单调递增。RabbitMQ/Outbox 采用至少一次投递，
        // 重复消息或乱序到达的旧 UPSERT/DELETE 都不能覆盖已经成功落地的新版本。
        return state.getContentVersion() != null && state.getContentVersion() >= command.contentVersion();
    }
}
