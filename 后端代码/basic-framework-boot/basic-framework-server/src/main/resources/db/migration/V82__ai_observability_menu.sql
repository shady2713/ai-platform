-- Q03：运行监控页面与重试权限点。
-- 说明：
--   1) 用量与限额菜单（4115，权限 ai:usage:query）与权限点由 Q02 的 V81 预置，页面 `ai/usage/index`
--      随本卡交付，故此处不改动 4115；
--   2) 新增"运行监控"页（4116，权限 ai:observability:query）：运行列表/步骤/失败原因/耗时分解，
--      与前端的 apps/web-ele/src/views/ai/observability 一一对应；
--   3) 重试是**独立动作**，单独建权限点（4117，type=3 按钮，权限 ai:observability:retry）：
--      查看与操作分别鉴权，无重试权限时界面按钮不显示，后端仍会拒绝（AT-011 口径）。

INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4116, '运行监控', 'ai:observability:query', 2, 15, 4000, 'observability', 'ep:monitor',
        'ai/observability/index', 'AiObservability', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0');

INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4117, '运行重试', 'ai:observability:retry', 3, 16, 4116, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
