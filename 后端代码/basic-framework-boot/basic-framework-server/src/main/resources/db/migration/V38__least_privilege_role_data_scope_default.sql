-- ORM 之外的受控写入也必须继承最小权限默认值；存量角色保留管理员已显式配置的范围。
ALTER TABLE `system_role`
    ALTER COLUMN `data_scope` SET DEFAULT 5;
