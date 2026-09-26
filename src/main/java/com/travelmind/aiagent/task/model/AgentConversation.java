package com.travelmind.aiagent.task.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_conversation")
public class AgentConversation {
    @TableId
    private String conversationId;
    private Long userId;
    private String title;
    private String lastMessagePreview;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
