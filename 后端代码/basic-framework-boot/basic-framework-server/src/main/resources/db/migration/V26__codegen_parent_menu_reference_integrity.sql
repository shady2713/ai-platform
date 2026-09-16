-- 非管理后台场景不生成管理菜单，清除历史隐藏引用。
UPDATE `infra_codegen_table`
SET `parent_menu_id` = NULL
WHERE `scene` <> 1;

-- 管理后台草稿允许暂不选择菜单；已填写但失效的引用安全回退到根菜单。
UPDATE `infra_codegen_table` codegen_table
LEFT JOIN `system_menu` parent_menu
  ON parent_menu.`id` = codegen_table.`parent_menu_id` AND parent_menu.`deleted` = b'0'
SET codegen_table.`parent_menu_id` = 0,
    codegen_table.`updater` = '1',
    codegen_table.`update_time` = '2026-08-24 00:00:00'
WHERE codegen_table.`deleted` = b'0'
  AND codegen_table.`scene` = 1
  AND codegen_table.`parent_menu_id` IS NOT NULL
  AND codegen_table.`parent_menu_id` <> 0
  AND (parent_menu.`id` IS NULL OR parent_menu.`type` NOT IN (1, 2));

-- 未知场景无法安全推断生成物；软删除配置并归一为无菜单的 APP 形状。
UPDATE `infra_codegen_table`
SET `deleted` = b'1',
    `scene` = 2,
    `parent_menu_id` = NULL,
    `updater` = '1',
    `update_time` = '2026-08-24 00:00:00'
WHERE `scene` NOT IN (1, 2);

UPDATE `infra_codegen_column` column_definition
JOIN `infra_codegen_table` table_definition ON table_definition.`id` = column_definition.`table_id`
SET column_definition.`deleted` = b'1',
    column_definition.`updater` = '1',
    column_definition.`update_time` = '2026-08-24 00:00:00'
WHERE column_definition.`deleted` = b'0'
  AND table_definition.`deleted` = b'1';

ALTER TABLE `infra_codegen_table`
    ADD KEY `idx_parent_menu_id` (`parent_menu_id`),
    ADD CONSTRAINT `chk_codegen_scene_parent_menu_shape`
        CHECK (`deleted` = b'1'
            OR (`scene` = 1 AND (`parent_menu_id` IS NULL OR `parent_menu_id` >= 0))
            OR (`scene` = 2 AND `parent_menu_id` IS NULL));
