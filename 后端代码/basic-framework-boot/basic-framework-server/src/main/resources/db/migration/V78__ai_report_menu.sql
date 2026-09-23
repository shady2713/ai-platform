-- R07：个人报表预览菜单与权限点（4100，挂在 4000 AI 中台目录下）。
-- 说明：报表本身是**应用端私人数据**（归属 = 应用 + 主体 + 外部用户标识），管理端没有跨主体读取入口；
-- 本页面用应用客户端凭据换取短期受限票据后走应用端通道（与 O08 在线调试同一口径），
-- 因此该权限码只用于**菜单可见性**，不作为接口鉴权码（接口鉴权走票据主体）。

INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4100, '个人报表', 'ai:report:preview', 2, 11, 4000, 'report', 'ep:data-analysis',
        'ai/report/index', 'AiReport', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0');
