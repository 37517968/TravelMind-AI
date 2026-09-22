ALTER TABLE `outbox_event`
    ADD COLUMN `exchange_name` VARCHAR(128) NOT NULL DEFAULT 'agent.command' AFTER `event_type`;

ALTER TABLE `travel_plan`
    ADD COLUMN `knowledge_version` BIGINT NOT NULL DEFAULT 1 COMMENT '知识内容版本' AFTER `inKnowledgeBase`;

CREATE TABLE IF NOT EXISTS `knowledge_index_state` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `source_type` VARCHAR(32) NOT NULL,
    `source_id` BIGINT NOT NULL,
    `content_version` BIGINT NOT NULL,
    `content_hash` VARCHAR(64) NOT NULL,
    `index_status` VARCHAR(24) NOT NULL,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    `chunk_count` INT NOT NULL DEFAULT 0,
    `embedding_model_version` VARCHAR(64) NULL,
    `last_event_id` VARCHAR(64) NULL,
    `last_error` VARCHAR(1024) NULL,
    `last_indexed_at` DATETIME(3) NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_source` (`source_type`, `source_id`),
    KEY `idx_knowledge_state_status` (`index_status`, `updated_at`),
    KEY `idx_knowledge_state_version` (`source_type`, `content_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识源索引版本与对账状态';

CREATE TABLE IF NOT EXISTS `knowledge_chunk` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `chunk_id` VARCHAR(160) NOT NULL,
    `document_id` VARCHAR(128) NOT NULL,
    `source_type` VARCHAR(32) NOT NULL,
    `source_id` BIGINT NOT NULL,
    `content_version` BIGINT NOT NULL,
    `sequence_no` INT NOT NULL,
    `title` VARCHAR(512) NULL,
    `content` LONGTEXT NOT NULL,
    `city` VARCHAR(128) NULL,
    `district` VARCHAR(128) NULL,
    `travel_type` VARCHAR(64) NULL,
    `tags` VARCHAR(512) NULL,
    `quality_score` DOUBLE NOT NULL DEFAULT 0,
    `like_count` INT NOT NULL DEFAULT 0,
    `content_hash` VARCHAR(64) NOT NULL,
    `embedding_model_version` VARCHAR(64) NOT NULL,
    `status` VARCHAR(24) NOT NULL,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    `published_at` DATETIME(3) NULL,
    `valid_from` DATETIME(3) NULL,
    `valid_to` DATETIME(3) NULL,
    `metadata_json` JSON NULL,
    `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_chunk_id` (`chunk_id`),
    KEY `idx_knowledge_chunk_source` (`source_type`, `source_id`, `content_version`),
    KEY `idx_knowledge_chunk_filter` (`city`, `district`, `travel_type`, `status`, `is_deleted`),
    KEY `idx_knowledge_chunk_hash` (`content_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可追溯知识切片事实表';
