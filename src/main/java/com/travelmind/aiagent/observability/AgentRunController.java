package com.travelmind.aiagent.observability;

import com.travelmind.aiagent.annotation.AuthCheck;
import com.travelmind.aiagent.constant.UserConstant;
import com.travelmind.aiagent.observability.dto.AgentRunView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/agent/runs")
@RequiredArgsConstructor
@Tag(name = "Agent运行可观测")
public class AgentRunController {
    private final AgentRunQueryService service;

    @GetMapping("/{taskId}")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @Operation(summary = "按 taskId 查看脱敏后的执行段、工作流节点和工具调用")
    public AgentRunView get(@PathVariable Long taskId) {
        return service.get(taskId);
    }
}
