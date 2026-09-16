-- 菜单父级使用 0 表示根节点；先保留有效子树并修复直接孤儿。
UPDATE `system_menu` child
LEFT JOIN `system_menu` parent
       ON parent.`id` = child.`parent_id` AND parent.`deleted` = b'0'
SET child.`parent_id` = 0,
    child.`updater` = '1',
    child.`update_time` = '2026-08-24 00:00:00'
WHERE child.`deleted` = b'0'
  AND child.`parent_id` <> 0
  AND parent.`id` IS NULL;

-- 目录(1)和菜单(2)才能作为父级；非法父级的直接子树入口改挂根节点。
UPDATE `system_menu` child
JOIN `system_menu` parent
  ON parent.`id` = child.`parent_id` AND parent.`deleted` = b'0'
SET child.`parent_id` = 0,
    child.`updater` = '1',
    child.`update_time` = '2026-08-24 00:00:00'
WHERE child.`deleted` = b'0'
  AND parent.`type` NOT IN (1, 2);

-- 找出剩余循环中的每个菜单并改挂根节点；非循环后代保持原有父子关系。
CREATE TEMPORARY TABLE `tmp_menu_parent_cycle` (
    `menu_id` bigint NOT NULL,
    PRIMARY KEY (`menu_id`)
) ENGINE=InnoDB;

INSERT INTO `tmp_menu_parent_cycle` (`menu_id`)
WITH RECURSIVE `parent_path` AS (
    SELECT menu.`id` AS `current_id`,
           menu.`parent_id`,
           CAST(CONCAT(',', menu.`id`, ',') AS CHAR(20000)) AS `visited_path`,
           CAST(NULL AS SIGNED) AS `cycle_id`
    FROM `system_menu` menu
    WHERE menu.`deleted` = b'0'

    UNION ALL

    SELECT parent.`id` AS `current_id`,
           parent.`parent_id`,
           CONCAT(path.`visited_path`, parent.`id`, ','),
           CASE
               WHEN LOCATE(CONCAT(',', parent.`id`, ','), path.`visited_path`) > 0 THEN parent.`id`
               ELSE NULL
           END AS `cycle_id`
    FROM `parent_path` path
    JOIN `system_menu` parent
      ON parent.`id` = path.`parent_id` AND parent.`deleted` = b'0'
    WHERE path.`parent_id` <> 0
      AND path.`cycle_id` IS NULL
)
SELECT DISTINCT `cycle_id`
FROM `parent_path`
WHERE `cycle_id` IS NOT NULL;

UPDATE `system_menu` menu
JOIN `tmp_menu_parent_cycle` cycle_menu ON cycle_menu.`menu_id` = menu.`id`
SET menu.`parent_id` = 0,
    menu.`updater` = '1',
    menu.`update_time` = '2026-08-24 00:00:00';

DROP TEMPORARY TABLE `tmp_menu_parent_cycle`;

ALTER TABLE `system_menu`
    ADD CONSTRAINT `chk_menu_parent_id_non_negative`
        CHECK (`parent_id` >= 0);
