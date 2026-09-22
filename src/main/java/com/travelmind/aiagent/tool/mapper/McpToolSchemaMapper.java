package com.travelmind.aiagent.tool.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelmind.aiagent.tool.model.McpToolSchema;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface McpToolSchemaMapper extends BaseMapper<McpToolSchema> {
    @Select("SELECT * FROM mcp_tool_schema WHERE server_name=#{server} AND tool_name=#{tool} " +
            "AND schema_version=#{version} LIMIT 1")
    McpToolSchema selectOneVersion(@Param("server") String server, @Param("tool") String tool,
                                   @Param("version") String version);
}
