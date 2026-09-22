-- Initial schema captured from sql/create_tables.sql.
-- Keep legacy column names for compatibility with the current MyBatis mappings.
-- Default accounts are intentionally not seeded by a production migration.

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `userAccount` VARCHAR(256) NOT NULL COMMENT '用户账号',
    `userPassword` VARCHAR(512) NOT NULL COMMENT '用户密码（加密后）',
    `userName` VARCHAR(256) NULL DEFAULT NULL COMMENT '用户昵称',
    `userAvatar` VARCHAR(1024) NULL DEFAULT NULL COMMENT '用户头像',
    `userProfile` VARCHAR(512) NULL DEFAULT NULL COMMENT '用户简介',
    `userRole` VARCHAR(256) NOT NULL DEFAULT 'user' COMMENT '用户角色：user/admin',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_userAccount` (`userAccount`),
    KEY `idx_userName` (`userName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `travel_plan` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `userId` BIGINT NOT NULL COMMENT '用户ID',
    `title` VARCHAR(256) NOT NULL COMMENT '方案标题',
    `destination` VARCHAR(128) NOT NULL COMMENT '目的地',
    `days` INT NOT NULL COMMENT '行程天数',
    `budget` INT NULL DEFAULT NULL COMMENT '预算（元）',
    `travelers` INT NULL DEFAULT NULL COMMENT '出行人数',
    `travelType` VARCHAR(64) NULL DEFAULT NULL COMMENT '旅行类型',
    `content` TEXT NOT NULL COMMENT '方案详情（Markdown格式）',
    `summary` VARCHAR(512) NULL DEFAULT NULL COMMENT '方案摘要',
    `coverImage` VARCHAR(1024) NULL DEFAULT NULL COMMENT '封面图片URL',
    `tags` VARCHAR(256) NULL DEFAULT NULL COMMENT '标签（逗号分隔）',
    `likeCount` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
    `favoriteCount` INT NOT NULL DEFAULT 0 COMMENT '收藏数',
    `commentCount` INT NOT NULL DEFAULT 0 COMMENT '评论数',
    `viewCount` INT NOT NULL DEFAULT 0 COMMENT '浏览数',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-草稿 1-已发布 2-已下架',
    `inKnowledgeBase` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已加入知识库：0-否 1-是',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_userId` (`userId`),
    KEY `idx_destination` (`destination`),
    KEY `idx_status` (`status`),
    KEY `idx_createTime` (`createTime`),
    KEY `idx_likeCount` (`likeCount`),
    KEY `idx_inKnowledgeBase` (`inKnowledgeBase`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅行方案表';

CREATE TABLE IF NOT EXISTS `travel_comment` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `planId` BIGINT NOT NULL COMMENT '旅行方案ID',
    `userId` BIGINT NOT NULL COMMENT '用户ID',
    `content` TEXT NOT NULL COMMENT '评论内容',
    `parentId` BIGINT NULL DEFAULT NULL COMMENT '父评论ID',
    `replyUserId` BIGINT NULL DEFAULT NULL COMMENT '被回复用户ID',
    `likeCount` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-正常 1-已删除',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updateTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    KEY `idx_planId` (`planId`),
    KEY `idx_userId` (`userId`),
    KEY `idx_parentId` (`parentId`),
    KEY `idx_createTime` (`createTime`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='旅行方案评论表';

CREATE TABLE IF NOT EXISTS `user_like` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `userId` BIGINT NOT NULL COMMENT '用户ID',
    `targetId` BIGINT NOT NULL COMMENT '目标ID（方案ID或评论ID）',
    `targetType` VARCHAR(32) NOT NULL COMMENT '旧结构目标类型：plan/comment 或 1/2',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_target` (`userId`, `targetId`, `targetType`),
    KEY `idx_targetId` (`targetId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户点赞记录表';

CREATE TABLE IF NOT EXISTS `user_favorite` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `userId` BIGINT NOT NULL COMMENT '用户ID',
    `planId` BIGINT NOT NULL COMMENT '方案ID',
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `isDelete` TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_plan` (`userId`, `planId`),
    KEY `idx_planId` (`planId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户收藏记录表';
