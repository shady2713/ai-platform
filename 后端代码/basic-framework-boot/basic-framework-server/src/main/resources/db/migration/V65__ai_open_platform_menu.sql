-- O08：开放平台目录菜单。
-- 设计要点：
--   1) 菜单只承载**目录与帮助**的可见性（权限码 ai:open-platform:query 仅用于前端菜单判断），
--      目录里的接口调用走应用端通道（应用客户端凭据换短期票据），不复用管理端权限；
--   2) 因此该权限码不用于任何后端接口鉴权，登记在 permission-catalog.json 的 unusedCatalog
--      （与 system:user:list 同类：菜单级权限，不作为接口鉴权码）。

INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4040, 'AI 开放平台', 'ai:open-platform:query', 2, 5, 4000, 'open-platform', 'ep:link',
        'ai/open-platform/index', 'AiOpenPlatform', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0');
