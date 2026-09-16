-- V43：清理已移除功能的字典残留（ADR 0029 / ADR 0033）
-- 腾讯云短信渠道客户端已删除，渠道字典仅保留阿里云；
-- 社交登录与短信登录端点已删除，登录类型字典仅保留账号登录与两种登出。
-- 沿用 V4/V31 先例：按字典类型 + 值匹配删除，避免硬编码自增 id。
DELETE FROM `system_dict_data`
WHERE (`dict_type` = 'system_sms_channel_code' AND `value` = 'TENCENT')
   OR (`dict_type` = 'system_login_type' AND `value` IN ('101', '103'));
