package com.travelmind.aiagent.knowledge.service;

import com.travelmind.aiagent.knowledge.mapper.KnowledgeChunkMapper;
import com.travelmind.aiagent.knowledge.mapper.KnowledgeIndexStateMapper;
import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import com.travelmind.aiagent.knowledge.model.KnowledgeIndexState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeStateStore {
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeIndexStateMapper stateMapper;

    public KnowledgeIndexState state(String sourceType, Long sourceId) {
        return stateMapper.selectBySource(sourceType, sourceId);
    }

    public List<KnowledgeChunk> activeChunks(String sourceType, Long sourceId) {
        return chunkMapper.selectActiveBySource(sourceType, sourceId);
    }

    @Transactional
    public void indexed(String eventId, String sourceType, Long sourceId, long version,
                        String documentHash, List<KnowledgeChunk> chunks) {
        chunkMapper.tombstoneBySource(sourceType, sourceId);
        for (KnowledgeChunk chunk : chunks) chunkMapper.insert(chunk);
        KnowledgeIndexState state = stateMapper.selectBySource(sourceType, sourceId);
        if (state == null) {
            state = new KnowledgeIndexState();
            state.setSourceType(sourceType);
            state.setSourceId(sourceId);
        }
        state.setContentVersion(version);
        state.setContentHash(documentHash);
        state.setIndexStatus("INDEXED");
        state.setIsDeleted(false);
        state.setChunkCount(chunks.size());
        state.setEmbeddingModelVersion("dashscope-text-embedding-v1");
        state.setLastEventId(eventId);
        state.setLastError(null);
        state.setLastIndexedAt(LocalDateTime.now());
        if (state.getId() == null) stateMapper.insert(state); else stateMapper.updateById(state);
    }

    @Transactional
    public void deleted(String eventId, String sourceType, Long sourceId, long version) {
        chunkMapper.tombstoneBySource(sourceType, sourceId);
        KnowledgeIndexState state = stateMapper.selectBySource(sourceType, sourceId);
        if (state == null) {
            state = new KnowledgeIndexState();
            state.setSourceType(sourceType);
            state.setSourceId(sourceId);
        }
        state.setContentVersion(version);
        state.setContentHash("");
        state.setIndexStatus("INDEXED");
        state.setIsDeleted(true);
        state.setChunkCount(0);
        state.setLastEventId(eventId);
        state.setLastError(null);
        state.setLastIndexedAt(LocalDateTime.now());
        if (state.getId() == null) stateMapper.insert(state); else stateMapper.updateById(state);
    }

    public void failed(String sourceType, Long sourceId, long version, String eventId, Throwable error) {
        KnowledgeIndexState state = stateMapper.selectBySource(sourceType, sourceId);
        if (state == null) {
            state = new KnowledgeIndexState();
            state.setSourceType(sourceType);
            state.setSourceId(sourceId);
            state.setContentHash("");
            state.setChunkCount(0);
            state.setIsDeleted(false);
        }
        state.setContentVersion(version);
        state.setIndexStatus("FAILED");
        state.setLastEventId(eventId);
        String message = error.getMessage();
        state.setLastError(message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(1000, message.length())));
        if (state.getId() == null) stateMapper.insert(state); else stateMapper.updateById(state);
    }
}
