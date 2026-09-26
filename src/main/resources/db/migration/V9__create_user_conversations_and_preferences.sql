CREATE TABLE IF NOT EXISTS `agent_conversation` (
    `conversation_id` VARCHAR(128) NOT NULL,
    `user_id` BIGINT NOT NULL,
    `title` VARCHAR(120) NOT NULL DEFAULT '新旅行会话',
    `last_message_preview` VARCHAR(240) NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`conversation_id`),
    KEY `idx_agent_conversation_user_updated` (`user_id`, `status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户拥有的 Agent 会话';

-- 只回填能够确定所属用户的历史会话；匿名历史任务不会被任何登录用户接管。
INSERT IGNORE INTO `agent_conversation` (`conversation_id`, `user_id`, `title`, `status`, `created_at`, `updated_at`)
SELECT `conversation_id`, `user_id`, '历史旅行会话', 'ACTIVE', MIN(`created_at`), MAX(`updated_at`)
FROM `agent_task`
WHERE `user_id` IS NOT NULL AND `conversation_id` IS NOT NULL AND `conversation_id` <> ''
GROUP BY `conversation_id`, `user_id`;

CREATE TABLE IF NOT EXISTS `user_travel_preference` (
    `user_id` BIGINT NOT NULL,
    `preferences_json` JSON NOT NULL,
    `version` INT NOT NULL DEFAULT 1,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可跨会话共享的显式旅行偏好';
