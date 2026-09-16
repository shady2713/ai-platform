-- 当前操作审计使用业务模块/动作字符串；仅移除未改动的旧数字操作类型种子。
DELETE FROM system_dict_data
WHERE dict_type = 'infra_operate_type' AND status = 0 AND deleted = b'0'
  AND (id, value, label) IN (
    (16, '0', '其它'), (17, '1', '查询'), (18, '2', '新增'),
    (19, '3', '修改'), (20, '4', '删除'), (22, '5', '导出'), (23, '6', '导入')
  );

DELETE FROM system_dict_type
WHERE id = 9 AND type = 'infra_operate_type' AND name = '操作类型'
  AND status = 0 AND deleted = b'0'
  AND NOT EXISTS (SELECT 1 FROM system_dict_data WHERE dict_type = 'infra_operate_type');

-- 示例部门只在未激活、组织结构和授权范围均未扩展的初始化实例中清理。
-- 已改名、启停、绑定联系方式、新增用户/部门或配置部门范围的安装保留原组织。
SET @prune_example_departments = (
  (SELECT COUNT(*) FROM system_users) = 1
  AND EXISTS (
    SELECT 1 FROM system_users
    WHERE id = 1 AND username = 'admin' AND password = '!bootstrap-required'
      AND status = 1 AND must_change_password = b'1' AND deleted = b'0' AND dept_id = 103
  )
  AND (SELECT COUNT(*) FROM system_dept) = 3
  AND (SELECT COUNT(*) FROM system_dept
       WHERE status = 0 AND deleted = b'0' AND phone IS NULL AND email IS NULL
         AND ((id = 100 AND name = '总公司' AND parent_id = 0 AND sort = 0 AND leader_user_id = 1)
           OR (id = 101 AND name = '深圳总公司' AND parent_id = 100 AND sort = 1 AND leader_user_id IS NULL)
           OR (id = 103 AND name = '研发部门' AND parent_id = 101 AND sort = 1 AND leader_user_id = 1))) = 3
  AND NOT EXISTS (SELECT 1 FROM system_role WHERE JSON_LENGTH(data_scope_dept_ids) > 0)
  AND NOT EXISTS (SELECT 1 FROM system_user_session)
);

UPDATE system_users SET dept_id = 100, update_time = update_time
WHERE @prune_example_departments AND id = 1;
DELETE FROM system_dept WHERE @prune_example_departments AND id = 103;
DELETE FROM system_dept WHERE @prune_example_departments AND id = 101;
SET @prune_example_departments = NULL;
