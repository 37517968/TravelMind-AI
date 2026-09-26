package com.travelmind.aiagent.task.service;

import com.travelmind.aiagent.common.ErrorCode;
import com.travelmind.aiagent.exception.BusinessException;
import com.travelmind.aiagent.task.mapper.AgentConversationMapper;
import com.travelmind.aiagent.task.model.AgentConversation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentConversationService {
    private final AgentConversationMapper mapper;
    private final AgentConversationMemoryService memoryService;
    private final AgentPlanningDraftService draftService;

    @Transactional
    public AgentConversation create(Long userId, String requestedTitle) {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setUserId(userId);
        conversation.setTitle(normalizeTitle(requestedTitle, "新旅行会话"));
        conversation.setStatus("ACTIVE");
        mapper.insert(conversation);
        return mapper.selectOwned(userId, conversation.getConversationId());
    }

    public List<AgentConversation> list(Long userId) { return mapper.selectActiveByUser(userId); }

    public AgentConversation requireOwned(Long userId, String conversationId) {
        AgentConversation conversation = mapper.selectOwned(userId, conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "会话不存在或不属于当前用户");
        }
        return conversation;
    }

    public void touch(Long userId, String conversationId, String prompt) {
        String preview = normalizeTitle(prompt, "旅行规划");
        mapper.touch(userId, conversationId, preview, preview);
    }

    @Transactional
    public AgentConversation rename(Long userId, String conversationId, String title) {
        AgentConversation conversation = requireOwned(userId, conversationId);
        conversation.setTitle(normalizeTitle(title, conversation.getTitle()));
        mapper.updateById(conversation);
        return mapper.selectOwned(userId, conversationId);
    }

    @Transactional
    public void archive(Long userId, String conversationId) {
        AgentConversation conversation = requireOwned(userId, conversationId);
        conversation.setStatus("ARCHIVED");
        mapper.updateById(conversation);
        memoryService.clear(userId, conversationId);
        draftService.clear(userId, conversationId);
    }

    private String normalizeTitle(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value.strip();
        return result.substring(0, Math.min(result.length(), 120));
    }
}
