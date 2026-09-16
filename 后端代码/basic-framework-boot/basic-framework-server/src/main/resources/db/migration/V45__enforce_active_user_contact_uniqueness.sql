-- 可选联系方式以 NULL 表达缺失；空白值不参与唯一约束。
UPDATE `system_users` SET `mobile` = NULL WHERE TRIM(`mobile`) = '';
UPDATE `system_users` SET `email` = NULL WHERE TRIM(`email`) = '';

ALTER TABLE `system_users`
    ALTER COLUMN `mobile` SET DEFAULT NULL,
    ALTER COLUMN `email` SET DEFAULT NULL;

-- 只约束未删除用户；历史软删除记录不会阻止任意次数的联系方式复用。
-- 真实重复数据让迁移失败，由部署者核查归属后处理，禁止自动覆盖或删除。
ALTER TABLE `system_users`
    ADD UNIQUE INDEX `uk_system_users_mobile_active`
        ((CASE WHEN `deleted` = b'0' THEN NULLIF(TRIM(`mobile`), '') ELSE NULL END)),
    ADD UNIQUE INDEX `uk_system_users_email_active`
        ((CASE WHEN `deleted` = b'0' THEN NULLIF(TRIM(`email`), '') ELSE NULL END));
