-- V2：种子数据净化（安全基线）
-- 1. 公开注册链路已删除，移除注册开关配置；共享初始密码配置由后续激活流程替代
DELETE FROM `infra_config`
WHERE `config_key` IN ('system.user.register-enabled', 'system.user.init-password');

-- 2. 清空 admin 的演示手机号/邮箱与登录痕迹
UPDATE `system_users`
SET `mobile` = '', `email` = '', `login_ip` = '', `login_date` = NULL
WHERE `username` = 'admin'
  AND (`mobile` <> '' OR `email` <> '' OR `login_ip` <> '' OR `login_date` IS NOT NULL);

-- 3. 清空部门的演示电话/邮箱
UPDATE `system_dept`
SET `phone` = NULL, `email` = NULL
WHERE `phone` = '15888888888' OR `email` = 'ry@qq.com';

-- 4. 占位密钥的 OAuth2 客户端保持停用（status=1 禁用），须替换密钥后人工启用
UPDATE `system_oauth2_client`
SET `status` = 1
WHERE `secret` = 'PLEASE_CHANGE_ME' AND `status` <> 1;
