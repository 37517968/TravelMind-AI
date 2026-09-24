package com.travelmind.aiagent.tool.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmind.aiagent.governance.SentinelGovernanceService;
import com.travelmind.aiagent.tool.model.*;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ToolGateway {
    private static final Pattern FAILURE_MESSAGE = Pattern.compile("(出错|失败|异常)\\s*[:：]");
    private final ToolInputValidator validator;
    private final SentinelGovernanceService sentinel;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ToolAuditService audit;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final boolean highRiskEnabled;

    public ToolGateway(ToolInputValidator validator, SentinelGovernanceService sentinel,
                       StringRedisTemplate redis, RedissonClient redisson, ToolAuditService audit,
                       ObjectMapper objectMapper, ExecutorService toolExecutor,
                       @Value("${travel.tools.high-risk-enabled:false}") boolean highRiskEnabled,
                       Environment environment) {
        this.validator = validator;
        this.sentinel = sentinel;
        this.redis = redis;
        this.redisson = redisson;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.executor = toolExecutor;
        this.highRiskEnabled = highRiskEnabled && !environment.acceptsProfiles(Profiles.of("prod"));
    }

    public ToolResult execute(ToolPolicy policy, String inputSchema, String arguments,
                              ToolExecutionContext context, ToolInvocation invocation) {
        long started = System.nanoTime();
        String argumentsHash = sha256(arguments == null ? "" : arguments);
        int attempts = 0;
        ToolResult result;
        try {
            authorize(policy, context);
            validator.validate(arguments, inputSchema);
            String cacheKey = "tool:result:" + policy.toolName() + ":" + argumentsHash;
            ToolResult cached = policy.cacheable() ? read(cacheKey) : null;
            if (cached != null) {
                result = new ToolResult(cached.success(), cached.data(), cached.source(), cached.observedAt(),
                        cached.expiresAt(), cached.errorCode(), cached.retryable(), cached.degraded(), true,
                        cached.warnings());
                audit.record(context, policy, argumentsHash, result, 0, elapsed(started));
                return result;
            }

            RLock lock = distributedLock(policy, cacheKey);
            long leaseMillis = Math.max(5_000,
                    policy.timeout().toMillis() * Math.max(1, policy.maxAttempts()) + 5_000);
            boolean locked = tryLock(lock, leaseMillis);
            if (!locked) {
                ToolResult concurrent = readAfterShortWait(cacheKey);
                if (concurrent != null) {
                    result = new ToolResult(concurrent.success(), concurrent.data(), concurrent.source(), concurrent.observedAt(),
                            concurrent.expiresAt(), concurrent.errorCode(), concurrent.retryable(), true, true,
                            List.of("命中其他实例刚完成的缓存重建"));
                    audit.record(context, policy, argumentsHash, result, 0, elapsed(started));
                    return result;
                }
                throw new RejectedExecutionException("同一工具参数正在由其他实例刷新");
            }
            try {
                ToolResult doubleChecked = policy.cacheable() ? read(cacheKey) : null;
                if (doubleChecked != null) {
                    result = new ToolResult(doubleChecked.success(), doubleChecked.data(), doubleChecked.source(),
                            doubleChecked.observedAt(), doubleChecked.expiresAt(), doubleChecked.errorCode(),
                            doubleChecked.retryable(), doubleChecked.degraded(), true, doubleChecked.warnings());
                    audit.record(context, policy, argumentsHash, result, 0, elapsed(started));
                    return result;
                }
                Exception last = null;
                int allowedAttempts = policy.idempotent() ? Math.max(1, policy.maxAttempts()) : 1;
                for (attempts = 1; attempts <= allowedAttempts; attempts++) {
                    try {
                        String raw = invoke(policy, context, arguments, invocation);
                        if (looksLikeFailure(raw)) throw new IllegalStateException(raw);
                        String sanitized = sanitizeAndCrop(raw, policy.maxResultChars());
                        Instant observedAt = Instant.now();
                        result = new ToolResult(true, sanitized, policy.source(), observedAt,
                                policy.cacheable() ? observedAt.plus(policy.cacheTtl()) : null,
                                null, false, false, false, List.of());
                        if (policy.cacheable()) write(cacheKey, result, policy.cacheTtl().toSeconds());
                        audit.record(context, policy, argumentsHash, result, attempts, elapsed(started));
                        return result;
                    } catch (Exception failure) {
                        last = failure;
                        if (attempts < allowedAttempts) Thread.sleep(retryDelayMillis(failure, attempts));
                    }
                }
                throw last == null ? new IllegalStateException("Tool execution failed") : last;
            } finally {
                unlockQuietly(lock);
            }
        } catch (Exception failure) {
            String staleKey = "tool:stale:" + policy.toolName() + ":" + argumentsHash;
            ToolResult stale = policy.cacheable() ? read(staleKey) : null;
            if (stale != null) {
                result = new ToolResult(true, stale.data(), policy.source(), stale.observedAt(), stale.expiresAt(),
                        "STALE_FALLBACK", true, true, true, List.of("实时服务不可用，返回最近一次缓存"));
            } else {
                result = new ToolResult(false, null, policy.source(), Instant.now(), null,
                        errorCode(failure), retryable(failure), true, false,
                        List.of(safeMessage(failure)));
            }
            audit.record(context, policy, argumentsHash, result, attempts, elapsed(started));
            return result;
        }
    }

    private String invoke(ToolPolicy policy, ToolExecutionContext context, String arguments,
                          ToolInvocation invocation) throws Exception {
        Future<String> future;
        try {
            future = executor.submit(() -> sentinel.executeTool("MCP".equals(policy.source()) ? "tool.mcp" : "tool." + policy.toolName(),
                    userQuotaResource(policy), context.userId(), () -> invocation.call(arguments)));
        } catch (RejectedExecutionException overloaded) {
            throw new RejectedExecutionException("工具隔离线程池已满", overloaded);
        }
        try {
            return future.get(policy.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new ToolTimeoutException(policy.toolName(), timeout);
        } catch (ExecutionException wrapped) {
            if (wrapped.getCause() instanceof Exception exception) throw exception;
            throw new IllegalStateException(wrapped.getCause());
        }
    }

    private void authorize(ToolPolicy policy, ToolExecutionContext context) {
        String node = context.workflowNode() == null ? "CHAT" : context.workflowNode();
        if (!policy.allowedNodes().isEmpty() && !policy.allowedNodes().contains(node))
            throw new SecurityException("当前工作流节点无权调用工具 " + policy.toolName());
        if (policy.riskLevel() == ToolRiskLevel.HIGH_RISK &&
                (!highRiskEnabled || !context.highRiskApproved() || !context.roles().contains("ADMIN")))
            throw new SecurityException("高风险工具未授权");
    }

    private ToolResult readAfterShortWait(String key) throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            Thread.sleep(50);
            ToolResult value = read(key);
            if (value != null) return value;
        }
        return null;
    }

    private RLock distributedLock(ToolPolicy policy, String cacheKey) {
        if (!policy.cacheable()) return null;
        try {
            return redisson.getLock("lock:" + cacheKey);
        } catch (RuntimeException redisUnavailable) {
            log.warn("Distributed tool lock unavailable, bypassing cache stampede protection: {}",
                    redisUnavailable.getMessage());
            return null;
        }
    }

    private boolean tryLock(RLock lock, long leaseMillis) throws InterruptedException {
        if (lock == null) return true;
        try {
            return lock.tryLock(100, leaseMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException redisUnavailable) {
            log.warn("Distributed tool lock failed, executing without lock: {}", redisUnavailable.getMessage());
            return true;
        }
    }

    private void unlockQuietly(RLock lock) {
        if (lock == null) return;
        try {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        } catch (RuntimeException redisUnavailable) {
            log.debug("Distributed tool unlock skipped: {}", redisUnavailable.getMessage());
        }
    }

    private ToolResult read(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, ToolResult.class);
        } catch (Exception ignored) { return null; }
    }

    private void write(String key, ToolResult result, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(key, json, Math.max(1, ttlSeconds), TimeUnit.SECONDS);
            redis.opsForValue().set(key.replace("tool:result:", "tool:stale:"), json,
                    Math.max(60, ttlSeconds * 6), TimeUnit.SECONDS);
        } catch (Exception cacheFailure) {
            log.debug("Tool cache write skipped: {}", cacheFailure.getMessage());
        }
    }

    /**
     * 本地工具用 "xxx出错：/xxx失败：" 文案表达上游故障，统一信封必须识别，否则失败结果会被缓存并当成成功证据。
     */
    private boolean looksLikeFailure(String value) {
        if (value == null || value.isBlank()) return true;
        return FAILURE_MESSAGE.matcher(value).find() || value.toLowerCase().startsWith("error");
    }

    private String sanitizeAndCrop(String value, int maxChars) {
        String sanitized = value
                .replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[邮箱已脱敏]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[手机号已脱敏]")
                .replaceAll("(?i)(api[_-]?key|token|authorization)[=: ]+[^,\\s}]+", "$1=[凭证已脱敏]");
        return sanitized.length() <= maxChars ? sanitized : sanitized.substring(0, maxChars) + "\n[结果已裁剪]";
    }

    private String errorCode(Exception failure) {
        if (failure instanceof SecurityException) return "TOOL_FORBIDDEN";
        if (failure instanceof IllegalArgumentException) return "TOOL_INVALID_ARGUMENT";
        if (failure instanceof ToolTimeoutException) return "TOOL_TIMEOUT";
        if (failure instanceof RejectedExecutionException) return "TOOL_OVERLOADED";
        if (failure instanceof SentinelGovernanceService.GovernanceBlockedException) return "TOOL_RATE_LIMITED";
        return "TOOL_UPSTREAM_ERROR";
    }

    private boolean retryable(Exception failure) {
        return failure instanceof ToolTimeoutException || failure instanceof RejectedExecutionException
                || failure instanceof SentinelGovernanceService.GovernanceBlockedException;
    }

    /** 地图节点会连续完成检索、详情补全和分段路线规划，使用独立的用户级配额。 */
    private String userQuotaResource(ToolPolicy policy) {
        return policy.toolName() != null && policy.toolName().startsWith("amap_")
                ? "tool.user.map" : "tool.user";
    }

    /** Sentinel 采用秒级窗口；被限流后必须跨过当前窗口再重试。 */
    private long retryDelayMillis(Exception failure, int attempts) {
        if (failure instanceof SentinelGovernanceService.GovernanceBlockedException) {
            return 1_100L + ThreadLocalRandom.current().nextLong(100L);
        }
        return Math.min(500L, 100L << (attempts - 1));
    }

    private String safeMessage(Exception failure) {
        return switch (errorCode(failure)) {
            case "TOOL_FORBIDDEN" -> "当前调用没有该工具权限";
            case "TOOL_INVALID_ARGUMENT" -> "工具参数校验失败";
            case "TOOL_TIMEOUT" -> "外部服务响应超时";
            case "TOOL_OVERLOADED" -> "工具服务繁忙，请稍后重试";
            case "TOOL_RATE_LIMITED" -> "工具调用已限流或熔断";
            default -> "外部工具暂时不可用";
        };
    }

    private long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @FunctionalInterface
    public interface ToolInvocation { String call(String arguments) throws Exception; }

    private static class ToolTimeoutException extends TimeoutException {
        ToolTimeoutException(String toolName, Throwable cause) { super(toolName + " timed out"); initCause(cause); }
    }
}
