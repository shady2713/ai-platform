-- S04：服务调试权限。
-- 设计要点：
--   1) 调试使用当前生效版本 + 显式测试主体，按测试主体的当前授权逐条判定发布版本绑定的资源动作，
--      因此调试不会比测试主体看得更多（不能越权）；
--   2) 调试会真实调用模型（外发策略、端点解析与计量与线上一致），因此与"切换发布版本"分开授权：
--      能改配置的人不一定能触发模型调用；
--   3) 菜单 id 取 4039，挂在 4030 服务菜单下（4036-4038 已被 S02 占用）。

INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4039, '服务调试', 'ai:service:debug', 3, 9, 4030, '', '', '', NULL,
        0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
