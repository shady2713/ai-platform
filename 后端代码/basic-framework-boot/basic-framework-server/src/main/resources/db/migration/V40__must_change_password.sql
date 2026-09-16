-- 首次登录强制修改密码：system_users 增加 must_change_password 标记。
-- 种子管理员（id=1）携带仓库内公开的固定 BCrypt 哈希，必须置位以在运行时强制轮换；
-- 存量普通账号保持 b'0'，不受影响。
ALTER TABLE `system_users`
    ADD COLUMN `must_change_password` bit(1) NOT NULL DEFAULT b'0'
        COMMENT '下次登录前必须修改密码' AFTER `login_date`;

UPDATE `system_users` SET `must_change_password` = b'1' WHERE `id` = 1;
