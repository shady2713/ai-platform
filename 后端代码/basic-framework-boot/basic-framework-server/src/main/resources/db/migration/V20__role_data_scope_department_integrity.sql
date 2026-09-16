-- 角色自定义数据范围只保留仍有效的部门；非自定义范围不保留无效的隐藏引用。
CREATE TEMPORARY TABLE `tmp_role_data_scope_dept` (
    `role_id` bigint NOT NULL,
    `dept_id` bigint NOT NULL,
    PRIMARY KEY (`role_id`, `dept_id`)
) ENGINE=InnoDB;

INSERT INTO `tmp_role_data_scope_dept` (`role_id`, `dept_id`)
SELECT DISTINCT r.`id`, scope_dept.`dept_id`
FROM `system_role` r
JOIN JSON_TABLE(
    CASE
        WHEN JSON_VALID(r.`data_scope_dept_ids`)
            THEN CASE
                WHEN JSON_TYPE(r.`data_scope_dept_ids`) = 'ARRAY' THEN r.`data_scope_dept_ids`
                ELSE JSON_ARRAY()
            END
        ELSE JSON_ARRAY()
    END,
    '$[*]' COLUMNS (`dept_id` bigint PATH '$' NULL ON ERROR)
) scope_dept ON TRUE
JOIN `system_dept` d ON d.`id` = scope_dept.`dept_id` AND d.`deleted` = b'0'
WHERE r.`data_scope` = 2
  AND scope_dept.`dept_id` IS NOT NULL;

UPDATE `system_role`
SET `data_scope_dept_ids` = JSON_ARRAY();

UPDATE `system_role` r
JOIN (
    SELECT `role_id`, JSON_ARRAYAGG(`dept_id`) AS `dept_ids`
    FROM `tmp_role_data_scope_dept`
    GROUP BY `role_id`
) valid_scope ON valid_scope.`role_id` = r.`id`
SET r.`data_scope_dept_ids` = valid_scope.`dept_ids`;

DROP TEMPORARY TABLE `tmp_role_data_scope_dept`;

ALTER TABLE `system_role`
    ALTER COLUMN `data_scope_dept_ids` SET DEFAULT ('[]'),
    ADD CONSTRAINT `chk_role_data_scope_dept_ids_json`
        CHECK (JSON_VALID(`data_scope_dept_ids`) AND JSON_TYPE(`data_scope_dept_ids`) = 'ARRAY');
