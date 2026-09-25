ALTER TABLE `outbox_event`
    ADD COLUMN `trace_parent` VARCHAR(128) NULL AFTER `payload_json`;

CREATE TABLE IF NOT EXISTS `agent_task_execution` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `task_id` BIGINT NOT NULL,
    `command_type` VARCHAR(64) NOT NULL,
    `message_id` VARCHAR(128) NULL,
    `trace_id` VARCHAR(64) NULL,
    `span_id` VARCHAR(32) NULL,
    `status` VARCHAR(32) NOT NULL,
    `worker_instance` VARCHAR(128) NULL,
    `started_at` DATETIME(3) NOT NULL,
    `finished_at` DATETIME(3) NULL,
    `duration_ms` BIGINT NULL,
    `error_type` VARCHAR(128) NULL,
    `error_message` VARCHAR(1024) NULL,
    PRIMARY KEY (`id`),
    KEY `idx_agent_execution_task_started` (`task_id`, `started_at`),
    KEY `idx_agent_execution_trace` (`trace_id`),
    CONSTRAINT `fk_agent_execution_task` FOREIGN KEY (`task_id`) REFERENCES `agent_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 每次创建、恢复、重试对应的执行段与 Trace 关联';

ALTER TABLE `tool_audit_log`
    ADD KEY `idx_tool_audit_request_time` (`request_id`, `created_at`);
