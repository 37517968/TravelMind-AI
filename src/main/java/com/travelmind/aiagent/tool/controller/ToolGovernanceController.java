package com.travelmind.aiagent.tool.controller;

import com.travelmind.aiagent.annotation.AuthCheck;
import com.travelmind.aiagent.constant.UserConstant;
import com.travelmind.aiagent.tool.service.ToolAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/tools")
@RequiredArgsConstructor
public class ToolGovernanceController {
    private final ToolAuditService auditService;

    @GetMapping("/stats")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public List<Map<String, Object>> stats() { return auditService.last24Hours(); }
}
