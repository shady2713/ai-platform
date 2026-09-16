-- 非子表/树表模板不得残留隐藏关系，避免模板切换后旧引用继续影响删除和审计。
UPDATE `infra_codegen_table`
SET `master_table_id` = NULL,
    `sub_join_column_id` = NULL,
    `sub_join_many` = NULL
WHERE `template_type` <> 15;

UPDATE `infra_codegen_table`
SET `tree_parent_column_id` = NULL,
    `tree_name_column_id` = NULL
WHERE `template_type` <> 2;

-- 未知模板无法安全推断生成语义；软删除配置并归一为单表形状，使数据库约束可持续执行。
UPDATE `infra_codegen_table`
SET `deleted` = b'1',
    `template_type` = 1,
    `updater` = '1',
    `update_time` = '2026-08-24 00:00:00'
WHERE `template_type` NOT IN (1, 2, 10, 11, 12, 15);

-- 子表必须引用活动主表模板，且关联字段必须属于子表自身；无法推断时整体软删除。
UPDATE `infra_codegen_table` child
LEFT JOIN `infra_codegen_table` master
  ON master.`id` = child.`master_table_id` AND master.`deleted` = b'0'
LEFT JOIN `infra_codegen_column` join_column
  ON join_column.`id` = child.`sub_join_column_id` AND join_column.`deleted` = b'0'
SET child.`deleted` = b'1',
    child.`updater` = '1',
    child.`update_time` = '2026-08-24 00:00:00'
WHERE child.`deleted` = b'0'
  AND child.`template_type` = 15
  AND (child.`sub_join_many` IS NULL
    OR child.`master_table_id` = child.`id`
    OR master.`id` IS NULL
    OR master.`template_type` NOT IN (10, 11, 12)
    OR join_column.`id` IS NULL
    OR join_column.`table_id` <> child.`id`);

-- 树父字段和树名称字段必须是当前表两个不同的活动字段。
UPDATE `infra_codegen_table` tree_table
LEFT JOIN `infra_codegen_column` parent_column
  ON parent_column.`id` = tree_table.`tree_parent_column_id` AND parent_column.`deleted` = b'0'
LEFT JOIN `infra_codegen_column` name_column
  ON name_column.`id` = tree_table.`tree_name_column_id` AND name_column.`deleted` = b'0'
SET tree_table.`deleted` = b'1',
    tree_table.`updater` = '1',
    tree_table.`update_time` = '2026-08-24 00:00:00'
WHERE tree_table.`deleted` = b'0'
  AND tree_table.`template_type` = 2
  AND (parent_column.`id` IS NULL
    OR parent_column.`table_id` <> tree_table.`id`
    OR name_column.`id` IS NULL
    OR name_column.`table_id` <> tree_table.`id`
    OR tree_table.`tree_parent_column_id` = tree_table.`tree_name_column_id`);

-- 与失效表同聚合的字段同步软删除，避免留下可见但不可达的字段配置。
UPDATE `infra_codegen_column` column_definition
JOIN `infra_codegen_table` table_definition ON table_definition.`id` = column_definition.`table_id`
SET column_definition.`deleted` = b'1',
    column_definition.`updater` = '1',
    column_definition.`update_time` = '2026-08-24 00:00:00'
WHERE column_definition.`deleted` = b'0'
  AND table_definition.`deleted` = b'1';

ALTER TABLE `infra_codegen_table`
    ADD KEY `idx_master_table_id` (`master_table_id`),
    ADD KEY `idx_sub_join_column_id` (`sub_join_column_id`),
    ADD KEY `idx_tree_parent_column_id` (`tree_parent_column_id`),
    ADD KEY `idx_tree_name_column_id` (`tree_name_column_id`),
    ADD CONSTRAINT `chk_codegen_template_type`
        CHECK (`deleted` = b'1' OR `template_type` IN (1, 2, 10, 11, 12, 15)),
    ADD CONSTRAINT `chk_codegen_sub_reference_shape`
        CHECK (`deleted` = b'1' OR (`template_type` = 15
                AND `master_table_id` IS NOT NULL
                AND `sub_join_column_id` IS NOT NULL
                AND `sub_join_many` IS NOT NULL)
            OR (`template_type` <> 15
                AND `master_table_id` IS NULL
                AND `sub_join_column_id` IS NULL
                AND `sub_join_many` IS NULL)),
    ADD CONSTRAINT `chk_codegen_tree_reference_shape`
        CHECK (`deleted` = b'1' OR (`template_type` = 2
                AND `tree_parent_column_id` IS NOT NULL
                AND `tree_name_column_id` IS NOT NULL
                AND `tree_parent_column_id` <> `tree_name_column_id`)
            OR (`template_type` <> 2
                AND `tree_parent_column_id` IS NULL
                AND `tree_name_column_id` IS NULL));
