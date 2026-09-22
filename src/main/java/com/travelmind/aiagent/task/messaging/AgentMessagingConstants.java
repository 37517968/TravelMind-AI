package com.travelmind.aiagent.task.messaging;

public final class AgentMessagingConstants {
    private AgentMessagingConstants() {}

    public static final String COMMAND_EXCHANGE = "agent.command";
    public static final String PLAN_QUEUE = "agent.plan.q";
    public static final String RETRY_10_QUEUE = "agent.plan.retry.10s.q";
    public static final String RETRY_60_QUEUE = "agent.plan.retry.60s.q";
    public static final String DLQ = "agent.plan.dlq";
    public static final String PLAN_CREATE = "plan.create";
    public static final String PLAN_MODIFY = "plan.modify";
    public static final String TASK_RESUME = "task.resume";
    public static final String DEAD_LETTER = "task.dead";

    public static final String KNOWLEDGE_EXCHANGE = "knowledge.event";
    public static final String KNOWLEDGE_QUEUE = "knowledge.index.q";
    public static final String KNOWLEDGE_RETRY_QUEUE = "knowledge.index.retry.30s.q";
    public static final String KNOWLEDGE_DLQ = "knowledge.index.dlq";
    public static final String KNOWLEDGE_UPSERT = "knowledge.upsert";
    public static final String KNOWLEDGE_DELETE = "knowledge.delete";
    public static final String KNOWLEDGE_RETRY = "knowledge.retry";
    public static final String KNOWLEDGE_DEAD = "knowledge.dead";
}
