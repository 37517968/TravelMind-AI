package com.travelmind.aiagent.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.tool.model.ToolAuditLog;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface ToolAuditLogMapper extends BaseMapper<ToolAuditLog> {
    @Select("SELECT tool_name AS toolName, COUNT(*) AS calls, SUM(success) AS successes, " +
            "SUM(cache_hit) AS cacheHits, AVG(duration_ms) AS averageMs " +
            "FROM tool_audit_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR) GROUP BY tool_name")
    List<Map<String, Object>> aggregateLast24Hours();
}
