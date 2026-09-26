-- 初始管理员仅用于首次登录和部署验收，上线后应尽快提供改密能力并修改默认密码。
-- 密码明文：123456；存储格式：PBKDF2-HMAC-SHA256 / 210000 iterations / 16-byte random salt。
INSERT INTO `user` (
    `userAccount`, `userPassword`, `userName`, `userRole`, `isDelete`, `createTime`, `updateTime`
) VALUES (
    'admin',
    'pbkdf2$210000$m35MXrdYG0aCabRO1SUlTw==$3CepHpP9evuVAEnHaZOhnUCP5bVaXORt6TRSUn0dc40=',
    '系统管理员',
    'admin',
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON DUPLICATE KEY UPDATE
    `userPassword` = VALUES(`userPassword`),
    `userName` = VALUES(`userName`),
    `userRole` = 'admin',
    `isDelete` = 0,
    `updateTime` = CURRENT_TIMESTAMP;
