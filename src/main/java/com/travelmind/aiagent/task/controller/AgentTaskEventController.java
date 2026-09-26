package com.travelmind.aiagent.task.controller;

import com.travelmind.aiagent.task.event.AgentProgressEventStore;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.service.AgentTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import jakarta.servlet.http.HttpServletRequest;
import com.travelmind.aiagent.service.UserService;

@RestController
@RequestMapping("/agent/tasks")
@RequiredArgsConstructor
@Slf4j
public class AgentTaskEventController {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private final StringRedisTemplate redisTemplate;
    private final AgentProgressEventStore eventStore;
    private final AgentTaskService taskService;
    private final ExecutorService agentNodeInvocationExecutor;
    private final UserService userService;

    @Value("${agent.task.sse-timeout-ms:180000}")
    private long timeoutMs;

    @GetMapping(value = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long taskId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletRequest request) {
        taskService.requireOwnedTask(taskId, userService.getLoginUser(request).getId());
        SseEmitter emitter = new SseEmitter(timeoutMs);
        agentNodeInvocationExecutor.execute(() -> stream(taskId, lastEventId, emitter));
        return emitter;
    }

    private void stream(Long taskId, String lastEventId, SseEmitter emitter) {
        String cursor = lastEventId == null || lastEventId.isBlank() ? "0-0" : lastEventId;
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(10)),
                        StreamOffset.create(eventStore.streamKey(taskId), ReadOffset.from(cursor)));
                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        cursor = record.getId().getValue();
                        Object payload = record.getValue().get("payload");
                        String type = String.valueOf(record.getValue().getOrDefault("type", "PROGRESS"));
                        String eventName = "TOKEN".equals(type) ? "token" : "progress";
                        emitter.send(SseEmitter.event().id(cursor).name(eventName)
                                .data(payload == null ? "{}" : payload));
                    }
                }
                AgentTask task = taskService.requireTask(taskId);
                if (TERMINAL.contains(task.getStatus())) {
                    emitter.send(SseEmitter.event().name("terminal").data(task.getStatus()));
                    emitter.complete();
                    return;
                }
            }
            emitter.complete();
        } catch (Exception error) {
            log.debug("SSE stream closed for task {}: {}", taskId, error.getMessage());
            try { emitter.send(SseEmitter.event().name("error").data(error.getMessage())); }
            catch (IOException ignored) { }
            emitter.complete();
        }
    }
}
