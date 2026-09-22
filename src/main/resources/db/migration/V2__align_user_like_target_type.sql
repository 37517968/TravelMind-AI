-- The Java entity uses integer constants: 1 = plan, 2 = comment.
-- Normalize legacy textual values before changing the column type.
UPDATE `user_like`
SET `targetType` = CASE
    WHEN LOWER(`targetType`) = 'plan' THEN '1'
    WHEN LOWER(`targetType`) = 'comment' THEN '2'
    WHEN `targetType` IN ('1', '2') THEN `targetType`
    ELSE '0'
END;

ALTER TABLE `user_like`
    MODIFY COLUMN `targetType` TINYINT NOT NULL COMMENT '目标类型：1-方案 2-评论';
