package com.travelmind.aiagent.task.controller;

import com.travelmind.aiagent.task.dto.AgentTaskCreateRequest;
import com.travelmind.aiagent.task.dto.AgentTaskResumeRequest;
import com.travelmind.aiagent.task.dto.AgentTaskView;
import com.travelmind.aiagent.task.model.AgentTask;
import com.travelmind.aiagent.task.service.AgentTaskService;
import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import com.travelmind.aiagent.task.service.AgentPlanningDraftService;
import com.travelmind.aiagent.task.service.AgentConversationService;
import com.travelmind.aiagent.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/agent/tasks")
@RequiredArgsConstructor
@Tag(name = "Agent异步任务")
public class AgentTaskController {
    private final AgentTaskService taskService;
    private final AgentConversationMemoryService conversationMemory;
    private final AgentPlanningDraftService planningDraftService;
    private final AgentConversationService conversationService;
    private final UserService userService;

    @PostMapping
    @Operation(summary = "提交旅行规划任务（立即返回，不等待模型）")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AgentTaskCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = userService.getLoginUser(httpRequest).getId();
        AgentTask task = taskService.submit(idempotencyKey, userId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "taskId", task.getId(), "requestId", task.getRequestId(), "status", task.getStatus()));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "查询任务、结果及节点检查点")
    public AgentTaskView get(@PathVariable Long taskId, HttpServletRequest request) {
        return taskService.get(taskId, userService.getLoginUser(request).getId());
    }

    @PostMapping("/{taskId}/pause")
    @Operation(summary = "在下一个安全点暂停任务")
    public AgentTask pause(@PathVariable Long taskId, HttpServletRequest request) {
        return taskService.pause(taskId, userService.getLoginUser(request).getId());
    }

    @PostMapping("/{taskId}/cancel")
    @Operation(summary = "在下一个安全点取消任务")
    public AgentTask cancel(@PathVariable Long taskId, HttpServletRequest request) {
        return taskService.cancel(taskId, userService.getLoginUser(request).getId());
    }

    @PostMapping("/{taskId}/resume")
    @Operation(summary = "补充参数并恢复任务")
    public AgentTask resume(@PathVariable Long taskId, @Valid @RequestBody AgentTaskResumeRequest request,
                            HttpServletRequest httpRequest) {
        return taskService.resume(taskId, userService.getLoginUser(httpRequest).getId(), request.getSupplemental());
    }

    @PostMapping("/{taskId}/nodes/{nodeId}/retry")
    @Operation(summary = "从已完成检查点恢复并重试失败节点")
    public AgentTask retryNode(@PathVariable Long taskId, @PathVariable String nodeId, HttpServletRequest request) {
        return taskService.retryNode(taskId, userService.getLoginUser(request).getId(), nodeId);
    }

    @DeleteMapping("/conversations/{conversationId}/memory")
    @Operation(summary = "清除统一任务入口的短期会话记忆和旅行规划草稿")
    public Map<String, Object> clearConversationMemory(@PathVariable String conversationId, HttpServletRequest request) {
        Long userId = userService.getLoginUser(request).getId();
        conversationService.requireOwned(userId, conversationId);
        conversationMemory.clear(userId, conversationId);
        planningDraftService.clear(userId, conversationId);
        return Map.of("success", true, "conversationId", conversationId);
    }
}
