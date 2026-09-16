-- V1 遗留了菜单类型、数据范围的内置字典数据，却缺少对应父类型；语义可由稳定类型键确定，先补齐父配置。
INSERT INTO `system_dict_type`
    (`name`, `type`, `status`, `remark`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT '菜单类型', 'system_menu_type', 0, 'V24 补齐内置字典类型', '1', '2026-08-24 00:00:00', '1',
       '2026-08-24 00:00:00', b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM `system_dict_type` WHERE `type` = 'system_menu_type'
);

INSERT INTO `system_dict_type`
    (`name`, `type`, `status`, `remark`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
SELECT '数据范围', 'system_data_scope', 0, 'V24 补齐内置字典类型', '1', '2026-08-24 00:00:00', '1',
       '2026-08-24 00:00:00', b'0'
WHERE NOT EXISTS (
    SELECT 1 FROM `system_dict_type` WHERE `type` = 'system_data_scope'
);

-- 其余缺失父类型的数据无法安全推断父配置，且当前框架无历史恢复要求，迁移删除无效配置。
DELETE data
FROM `system_dict_data` data
LEFT JOIN `system_dict_type` type ON type.`type` = data.`dict_type`
WHERE type.`id` IS NULL;

-- 已软删类型仍保留物理父行；将其活动字典数据同步软删，避免运行时继续暴露失效配置。
UPDATE `system_dict_data` data
JOIN `system_dict_type` type ON type.`type` = data.`dict_type`
SET data.`deleted` = b'1',
    data.`updater` = '1',
    data.`update_time` = '2026-08-24 00:00:00'
WHERE data.`deleted` = b'0'
  AND type.`deleted` = b'1';

ALTER TABLE `system_dict_data`
    ADD CONSTRAINT `fk_dict_data_type`
        FOREIGN KEY (`dict_type`) REFERENCES `system_dict_type` (`type`)
        ON UPDATE RESTRICT ON DELETE RESTRICT;
