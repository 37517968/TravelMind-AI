-- agent_task.workflow_version 由 formal-travel-stategraph-v5-map-plan 起长度超过原 VARCHAR(32)，
-- 插入会被 MySQL 以 1406 Data too long 拒绝，导致任务提交接口整体返回 50000。
ALTER TABLE `agent_task`
    MODIFY COLUMN `workflow_version` VARCHAR(64) NOT NULL;
