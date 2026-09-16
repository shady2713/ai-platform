DELETE role_menu
FROM `system_role_menu` role_menu
JOIN `system_menu` menu ON menu.`id` = role_menu.`menu_id`
WHERE menu.`id` = 115
   OR menu.`parent_id` = 115
   OR menu.`permission` LIKE 'infra:codegen:%';

DELETE FROM `system_menu`
WHERE `parent_id` = 115
   OR `id` = 115
   OR `permission` LIKE 'infra:codegen:%';

DELETE FROM `system_dict_data`
WHERE `dict_type` LIKE 'infra_codegen_%';

DELETE FROM `system_dict_type`
WHERE `type` LIKE 'infra_codegen_%';

DROP TABLE IF EXISTS `infra_codegen_column`;
DROP TABLE IF EXISTS `infra_codegen_table`;
