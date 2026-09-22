-- K09：知识检索调试菜单与权限点（4096，挂在 4090 知识库下）。
-- 说明：知识库菜单与权限点（4090-4095）已在 V72 建立；本卡新增"检索调试"权限点，
-- 调试台用指定有权主体检索并展示真实引用（过滤条件仍由服务端按授权生成）。

INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4096, '检索调试', 'ai:knowledge:debug', 3, 6, 4090, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
