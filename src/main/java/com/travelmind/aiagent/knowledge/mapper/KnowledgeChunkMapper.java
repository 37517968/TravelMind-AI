package com.travelmind.aiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.knowledge.model.KnowledgeChunk;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {
    @Select("SELECT * FROM knowledge_chunk WHERE source_type=#{sourceType} AND source_id=#{sourceId} AND is_deleted=0 ORDER BY sequence_no")
    List<KnowledgeChunk> selectActiveBySource(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Update("UPDATE knowledge_chunk SET is_deleted=1, status='DELETED', updated_at=NOW(3) WHERE source_type=#{sourceType} AND source_id=#{sourceId} AND is_deleted=0")
    int tombstoneBySource(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("SELECT * FROM knowledge_chunk WHERE is_deleted=0 AND status='ACTIVE' ORDER BY source_id, sequence_no")
    List<KnowledgeChunk> selectAllActive();

    @Select("SELECT DISTINCT source_id FROM knowledge_chunk WHERE source_type=#{sourceType}")
    List<Long> selectDistinctSourceIds(@Param("sourceType") String sourceType);

    @Update("UPDATE knowledge_chunk SET is_deleted=1, status='DELETED', updated_at=NOW(3) WHERE is_deleted=0")
    int tombstoneAllActive();

    @Select("SELECT COUNT(*) FROM knowledge_chunk WHERE is_deleted=0 AND status='ACTIVE'")
    long countActive();
}
