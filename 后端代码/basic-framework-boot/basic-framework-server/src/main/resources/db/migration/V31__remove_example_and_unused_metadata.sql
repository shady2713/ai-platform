-- 移除 CRM 示例业务和仅服务于已删除实现的元数据；保留终端、会员类型及应用端 API 接缝。

DELETE FROM `system_menu`
WHERE `id` IN (3002, 3003, 3004);

DELETE FROM `system_menu`
WHERE `id` = 3001;

DELETE FROM `system_menu`
WHERE `id` = 3000;

DROP TABLE IF EXISTS `crm_customer`;

-- 代码生成只交付管理后台模板；应用端通过 /app-api 契约独立对接。
DELETE FROM `infra_codegen_table`
WHERE `scene` <> 1;

ALTER TABLE `infra_codegen_table`
    DROP CHECK `chk_codegen_scene_parent_menu_shape`;

ALTER TABLE `infra_codegen_table`
    ADD CONSTRAINT `chk_codegen_scene_parent_menu_shape`
        CHECK (`deleted` = b'1'
            OR (`scene` = 1 AND (`parent_menu_id` IS NULL OR `parent_menu_id` >= 0)));

DELETE FROM `system_dict_data`
WHERE (`dict_type` = 'infra_codegen_scene' AND `value` = '2')
   OR (`dict_type` = 'infra_codegen_front_type' AND `value` IN ('10', '20', '30', '40', '41', '51'))
   OR `dict_type` = 'date_interval';

DELETE FROM `system_dict_type`
WHERE `type` = 'date_interval';
