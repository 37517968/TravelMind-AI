package com.travelmind.aiagent.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolInputValidatorTest {
    private final ToolInputValidator validator = new ToolInputValidator(new ObjectMapper());
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"url\"]}";

    @Test
    void shouldRejectMissingRequiredArgument() {
        assertThatThrownBy(() -> validator.validate("{}", SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void shouldRejectPrivateNetworkUrlToPreventSsrf() {
        assertThatThrownBy(() -> validator.validate("{\"url\":\"http://127.0.0.1/admin\"}", SCHEMA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }
}
