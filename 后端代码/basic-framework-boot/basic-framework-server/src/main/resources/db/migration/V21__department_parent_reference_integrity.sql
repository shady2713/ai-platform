-- 部门父级是使用 0 表示根节点的逻辑引用；先保留有效子树并修复直接孤儿。
UPDATE `system_dept` d
LEFT JOIN `system_dept` p ON p.`id` = d.`parent_id` AND p.`deleted` = b'0'
SET d.`parent_id` = 0,
    d.`updater` = '1',
    d.`update_time` = '2026-08-24 00:00:00'
WHERE d.`deleted` = b'0'
  AND d.`parent_id` <> 0
  AND p.`id` IS NULL;

-- 找出循环中的每个部门并改挂根节点；非循环后代保持原有父子关系。
CREATE TEMPORARY TABLE `tmp_dept_parent_cycle` (
    `dept_id` bigint NOT NULL,
    PRIMARY KEY (`dept_id`)
) ENGINE=InnoDB;

INSERT INTO `tmp_dept_parent_cycle` (`dept_id`)
WITH RECURSIVE `parent_path` AS (
    SELECT d.`id` AS `current_id`,
           d.`parent_id`,
           CAST(CONCAT(',', d.`id`, ',') AS CHAR(20000)) AS `visited_path`,
           CAST(NULL AS SIGNED) AS `cycle_id`
    FROM `system_dept` d
    WHERE d.`deleted` = b'0'

    UNION ALL

    SELECT p.`id` AS `current_id`,
           p.`parent_id`,
           CONCAT(path.`visited_path`, p.`id`, ','),
           CASE
               WHEN LOCATE(CONCAT(',', p.`id`, ','), path.`visited_path`) > 0 THEN p.`id`
               ELSE NULL
           END AS `cycle_id`
    FROM `parent_path` path
    JOIN `system_dept` p ON p.`id` = path.`parent_id` AND p.`deleted` = b'0'
    WHERE path.`parent_id` <> 0
      AND path.`cycle_id` IS NULL
)
SELECT DISTINCT `cycle_id`
FROM `parent_path`
WHERE `cycle_id` IS NOT NULL;

UPDATE `system_dept` d
JOIN `tmp_dept_parent_cycle` cycle_dept ON cycle_dept.`dept_id` = d.`id`
SET d.`parent_id` = 0,
    d.`updater` = '1',
    d.`update_time` = '2026-08-24 00:00:00';

DROP TEMPORARY TABLE `tmp_dept_parent_cycle`;

ALTER TABLE `system_dept`
    ADD CONSTRAINT `chk_dept_parent_id_non_negative`
        CHECK (`parent_id` >= 0);
