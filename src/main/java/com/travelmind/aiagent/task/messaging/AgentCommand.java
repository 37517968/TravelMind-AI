package com.travelmind.aiagent.task.messaging;

public record AgentCommand(String eventId, Long taskId, String commandType) {
}
