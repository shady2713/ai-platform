-- V4：mail 字典残留清理 + 布尔列类型统一（无生产数据，允许破坏性变更）

-- 1. 清理 system_mail_* 三张表（V3 已 DROP）遗留的字典种子
-- dict_type id=166 '邮件发送状态' + dict_data id=1223~1226 共 4 行
-- 按字典类型字符串匹配删除，避免硬编码自增 id
DELETE FROM `system_dict_data` WHERE `dict_type` = 'system_mail_send_status';
DELETE FROM `system_dict_type` WHERE `type` = 'system_mail_send_status';

-- 2. system_sms_code.used tinyint -> bit(1)
-- 全库布尔语义列（deleted/visible/keep_alive/always_show/read_status/success/master 等）
-- 已统一为 bit(1)，used（是否使用）是唯一仍以 tinyint 存储的布尔列；
-- Java 侧 SmsCodeDO.used 为 Boolean，bit(1) 与 MyBatis 布尔映射一致
-- 其余 tinyint 列（status/type/user_type/scene/result 等）为枚举或计数语义，
-- Java 侧均为 Integer，保持 tinyint 不变
ALTER TABLE `system_sms_code`
    MODIFY COLUMN `used` bit(1) NOT NULL COMMENT '是否使用';
