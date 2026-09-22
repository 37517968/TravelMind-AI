package com.travelmind.aiagent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.knowledge.model.KnowledgeIndexState;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface KnowledgeIndexStateMapper extends BaseMapper<KnowledgeIndexState> {
    @Select("SELECT * FROM knowledge_index_state WHERE source_type=#{sourceType} AND source_id=#{sourceId} LIMIT 1")
    KnowledgeIndexState selectBySource(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId);

    @Select("SELECT * FROM knowledge_index_state WHERE index_status NOT IN ('INDEXED', 'DELETED') ORDER BY updated_at LIMIT #{limit}")
    List<KnowledgeIndexState> selectInconsistent(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM knowledge_index_state WHERE index_status='FAILED'")
    long countFailed();
}
