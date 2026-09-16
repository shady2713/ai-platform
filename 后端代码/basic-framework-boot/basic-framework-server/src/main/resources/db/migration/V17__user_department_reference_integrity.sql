-- 用户所属部门是可空逻辑引用；迁移时解除指向已删除或不存在部门的历史引用。

UPDATE `system_users` u
LEFT JOIN `system_dept` d ON d.`id` = u.`dept_id` AND d.`deleted` = b'0'
SET u.`dept_id` = NULL,
    u.`updater` = '1',
    u.`update_time` = '2026-08-24 00:00:00'
WHERE u.`deleted` = b'0'
  AND u.`dept_id` IS NOT NULL
  AND d.`id` IS NULL;
