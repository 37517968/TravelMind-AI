package com.travelmind.aiagent.task;

import com.travelmind.aiagent.exception.BusinessException;
import com.travelmind.aiagent.task.mapper.AgentConversationMapper;
import com.travelmind.aiagent.task.model.AgentConversation;
import com.travelmind.aiagent.task.service.AgentConversationMemoryService;
import com.travelmind.aiagent.task.service.AgentConversationService;
import com.travelmind.aiagent.task.service.AgentPlanningDraftService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AgentConversationServiceTest {
    @Test
    void shouldRejectConversationOwnedByAnotherUser() {
        AgentConversationMapper mapper = mock(AgentConversationMapper.class);
        AgentConversationService service = new AgentConversationService(mapper,
                mock(AgentConversationMemoryService.class), mock(AgentPlanningDraftService.class));

        assertThatThrownBy(() -> service.requireOwned(2L, "conversation-of-user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前用户");
        verify(mapper).selectOwned(2L, "conversation-of-user-1");
    }

    @Test
    void archiveShouldClearOnlyTheOwnersScopedContext() {
        AgentConversationMapper mapper = mock(AgentConversationMapper.class);
        AgentConversationMemoryService memory = mock(AgentConversationMemoryService.class);
        AgentPlanningDraftService draft = mock(AgentPlanningDraftService.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation-1");
        conversation.setUserId(7L);
        conversation.setStatus("ACTIVE");
        when(mapper.selectOwned(7L, "conversation-1")).thenReturn(conversation);
        AgentConversationService service = new AgentConversationService(mapper, memory, draft);

        service.archive(7L, "conversation-1");

        verify(memory).clear(7L, "conversation-1");
        verify(draft).clear(7L, "conversation-1");
        verify(mapper).updateById(conversation);
    }
}
