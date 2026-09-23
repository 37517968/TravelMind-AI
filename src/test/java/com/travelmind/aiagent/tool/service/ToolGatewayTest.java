package com.travelmind.aiagent.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.governance.SentinelGovernanceService;
import com.travelmind.aiagent.tool.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolGatewayTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ToolAuditService audit = mock(ToolAuditService.class);
    private final SentinelGovernanceService sentinel = mock(SentinelGovernanceService.class);
    private final ToolGateway gateway = new ToolGateway(new ToolInputValidator(new ObjectMapper()),
            sentinel, mock(StringRedisTemplate.class), mock(RedissonClient.class),
            audit, new ObjectMapper().findAndRegisterModules(), executor, false, new MockEnvironment());

    @BeforeEach
    void passThroughSentinel() throws Exception {
        when(sentinel.executeTool(anyString(), anyString(), any())).thenAnswer(invocation ->
                ((Callable<?>) invocation.getArgument(2)).call());
    }

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void shouldRetryOnlyIdempotentToolAndReturnSuccess() {
        AtomicInteger calls = new AtomicInteger();
        ToolResult result = gateway.execute(policy(ToolRiskLevel.READ_ONLY, true, 2, Duration.ofSeconds(1)),
                "{\"type\":\"object\"}", "{}", ToolExecutionContext.anonymous(), input -> {
                    if (calls.incrementAndGet() == 1) throw new IllegalStateException("temporary");
                    return "ok";
                });

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        verify(audit).record(any(), any(), anyString(), eq(result), eq(2), anyLong());
    }

    @Test
    void shouldReturnUniformTimeoutError() {
        ToolResult result = gateway.execute(policy(ToolRiskLevel.READ_ONLY, true, 1, Duration.ofMillis(30)),
                "{\"type\":\"object\"}", "{}", ToolExecutionContext.anonymous(), input -> {
                    Thread.sleep(500);
                    return "late";
                });

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_TIMEOUT");
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void shouldTreatToolFailureMessageAsFailure() {
        ToolResult result = gateway.execute(policy(ToolRiskLevel.READ_ONLY, true, 1, Duration.ofSeconds(1)),
                "{\"type\":\"object\"}", "{}", ToolExecutionContext.anonymous(),
                input -> "搜索失败：INVALID_USER_KEY");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_UPSTREAM_ERROR");
        assertThat(result.data()).isNull();
    }

    @Test
    void shouldKeepStructuredToolPayloadAsSuccess() {
        ToolResult result = gateway.execute(policy(ToolRiskLevel.READ_ONLY, true, 1, Duration.ofSeconds(1)),
                "{\"type\":\"object\"}", "{}", ToolExecutionContext.anonymous(),
                input -> "{\"status\":\"1\",\"count\":\"3\",\"pois\":[]}");

        assertThat(result.success()).isTrue();
        assertThat(String.valueOf(result.data())).contains("count");
    }

    @Test
    void shouldDenyHighRiskToolUnlessExplicitlyEnabledAndApproved() {
        ToolResult result = gateway.execute(policy(ToolRiskLevel.HIGH_RISK, false, 1, Duration.ofSeconds(1)),
                "{\"type\":\"object\"}", "{}", ToolExecutionContext.anonymous(), input -> "never");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_FORBIDDEN");
    }

    @Test
    void shouldDenyHighRiskToolInProductionEvenWhenPropertyIsEnabled() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        ToolGateway productionGateway = new ToolGateway(new ToolInputValidator(new ObjectMapper()),
                sentinel, mock(StringRedisTemplate.class), mock(RedissonClient.class), audit,
                new ObjectMapper().findAndRegisterModules(), executor, true, production);
        ToolExecutionContext approvedAdmin = new ToolExecutionContext(
                "request-1", "admin-1", "CHAT", Set.of("ADMIN"), true);

        ToolResult result = productionGateway.execute(
                policy(ToolRiskLevel.HIGH_RISK, false, 1, Duration.ofSeconds(1)),
                "{\"type\":\"object\"}", "{}", approvedAdmin, input -> "never");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_FORBIDDEN");
    }

    private ToolPolicy policy(ToolRiskLevel risk, boolean idempotent, int attempts, Duration timeout) {
        return new ToolPolicy("demo", "LOCAL", risk, idempotent, false, Duration.ZERO, timeout,
                attempts, 1000, Set.of("CHAT"), 0);
    }
}
