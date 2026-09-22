package com.travelmind.aiagent.knowledge.controller;

import com.travelmind.aiagent.annotation.AuthCheck;
import com.travelmind.aiagent.constant.UserConstant;
import com.travelmind.aiagent.knowledge.model.HybridSearchResponse;
import com.travelmind.aiagent.knowledge.model.KnowledgeSearchRequest;
import com.travelmind.aiagent.knowledge.service.KnowledgeHybridSearchService;
import com.travelmind.aiagent.knowledge.service.KnowledgeIndexAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeIndexController {
    private final KnowledgeHybridSearchService searchService;
    private final KnowledgeIndexAdminService adminService;

    @PostMapping("/search")
    public HybridSearchResponse search(@RequestBody KnowledgeSearchRequest request) {
        return searchService.search(request);
    }

    @GetMapping("/admin/reconcile")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Map<String, Object> reconcile() { return adminService.reconcile(); }

    @PostMapping("/admin/rebuild")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public Map<String, Object> rebuild() { return adminService.rebuildBlueGreen(); }
}
