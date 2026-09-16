-- 代码生成表与列属于同一聚合；清理既有孤儿后，用外键保护最终物理清理。

DELETE c
FROM `infra_codegen_column` c
LEFT JOIN `infra_codegen_table` t ON t.`id` = c.`table_id`
WHERE t.`id` IS NULL;

ALTER TABLE `infra_codegen_column`
    ADD CONSTRAINT `fk_codegen_column_table`
        FOREIGN KEY (`table_id`) REFERENCES `infra_codegen_table` (`id`)
        ON DELETE CASCADE;
