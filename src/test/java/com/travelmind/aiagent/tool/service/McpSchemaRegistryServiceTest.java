package com.travelmind.aiagent.tool.service;

import com.travelmind.aiagent.tool.mapper.McpToolSchemaMapper;
import com.travelmind.aiagent.tool.model.McpToolSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class McpSchemaRegistryServiceTest {
    @Test
    void shouldRejectSchemaDriftWithoutVersionBump() {
        McpToolSchemaMapper mapper = mock(McpToolSchemaMapper.class);
        McpToolSchema existing = new McpToolSchema();
        existing.setSchemaHash("different-hash");
        when(mapper.selectOneVersion("maps", "route", "v1")).thenReturn(existing);

        McpSchemaRegistryService registry = new McpSchemaRegistryService(mapper);

        assertThatThrownBy(() -> registry.register("maps", "1.0", "route", "v1", "{\"type\":\"object\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("increment schema-version");
        verify(mapper, never()).updateById(any());
    }
}
