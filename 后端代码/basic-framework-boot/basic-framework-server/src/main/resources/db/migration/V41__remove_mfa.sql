-- 移除多因素认证存储；旧迁移保留以保持已部署数据库的校验和。
DROP TABLE IF EXISTS `system_user_mfa_recovery_code`;
DROP TABLE IF EXISTS `system_user_mfa_factor`;
