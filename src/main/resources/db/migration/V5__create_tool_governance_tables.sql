CREATE TABLE IF NOT EXISTS `tool_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `request_id` VARCHAR(64) NOT NULL,
    `user_id` VARCHAR(64) NOT NULL,
    `workflow_node` VARCHAR(64) NOT NULL,
    `tool_name` VARCHAR(128) NOT NULL,
    `tool_source` VARCHAR(32) NOT NULL,
    `risk_level` VARCHAR(24) NOT NULL,
    `arguments_hash` VARCHAR(64) NOT NULL,
    `success` TINYINT NOT NULL,
    `cache_hit` TINYINT NOT NULL DEFAULT 0,
    `degraded` TINYINT NOT NULL DEFAULT 0,
    `attempt_count` INT NOT NULL DEFAULT 1,
    `duration_ms` BIGINT NOT NULL,
    `error_code` VARCHAR(64) NULL,
    `estimated_cost` DECIMAL(12,6) NOT NULL DEFAULT 0,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_tool_audit_name_time` (`tool_name`, `created_at`),
    KEY `idx_tool_audit_user_time` (`user_id`, `created_at`),
    KEY `idx_tool_audit_success_time` (`success`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tool/MCP 调用审计与延迟事实表';

CREATE TABLE IF NOT EXISTS `mcp_tool_schema` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `server_name` VARCHAR(128) NOT NULL,
    `server_version` VARCHAR(64) NOT NULL,
    `tool_name` VARCHAR(128) NOT NULL,
    `schema_version` VARCHAR(64) NOT NULL,
    `schema_hash` VARCHAR(64) NOT NULL,
    `input_schema` JSON NOT NULL,
    `status` VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    `first_seen_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `last_seen_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mcp_tool_schema` (`server_name`, `tool_name`, `schema_version`),
    KEY `idx_mcp_schema_status` (`status`, `last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='远程 MCP Tool Schema 版本登记';
