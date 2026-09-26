package com.travelmind.aiagent.task.controller;

import com.travelmind.aiagent.model.entity.User;
import com.travelmind.aiagent.service.UserService;
import com.travelmind.aiagent.task.model.AgentConversation;
import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import com.travelmind.aiagent.task.service.AgentConversationService;
import com.travelmind.aiagent.task.service.UserTravelPreferenceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agent/conversations")
@RequiredArgsConstructor
public class AgentConversationController {
    private final UserService userService;
    private final AgentConversationService conversationService;
    private final AgentConversationMemoryService memoryService;
    private final UserTravelPreferenceService preferenceService;

    @GetMapping
    public List<AgentConversation> list(HttpServletRequest request) {
        return conversationService.list(user(request).getId());
    }

    @PostMapping
    public AgentConversation create(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        String title = body == null ? null : String.valueOf(body.getOrDefault("title", ""));
        return conversationService.create(user(request).getId(), title);
    }

    @PatchMapping("/{conversationId}")
    public AgentConversation rename(@PathVariable String conversationId, @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        return conversationService.rename(user(request).getId(), conversationId, String.valueOf(body.get("title")));
    }

    @DeleteMapping("/{conversationId}")
    public Map<String, Object> archive(@PathVariable String conversationId, HttpServletRequest request) {
        conversationService.archive(user(request).getId(), conversationId);
        return Map.of("success", true);
    }

    @GetMapping("/{conversationId}/messages")
    public List<Map<String, String>> messages(@PathVariable String conversationId, HttpServletRequest request) {
        Long userId = user(request).getId();
        conversationService.requireOwned(userId, conversationId);
        return memoryService.snapshot(userId, conversationId);
    }

    @GetMapping("/preferences")
    public Map<String, Object> preferences(HttpServletRequest request) {
        return preferenceService.get(user(request).getId());
    }

    @PutMapping("/preferences")
    public Map<String, Object> preferences(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return preferenceService.save(user(request).getId(), body);
    }

    private User user(HttpServletRequest request) { return userService.getLoginUser(request); }
}
