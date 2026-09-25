-- C09：Chat 集成菜单与主题页启用。
-- 说明：
--   1) 主题管理页（apps/web-ele 的 ai/theme）随本卡交付，故把 V79 预置的菜单 4105 由停用改为启用；
--   2) 新增"Chat 集成"页（4110）：展示嵌入入口、换票说明与 SDK 能力版本，只读展示，
--      因此复用已有的 ai:application:query 权限码（该页的输入就是"选哪个应用"），不新造权限码。

UPDATE `system_menu` SET `status` = 0, `updater` = '1', `update_time` = CURRENT_TIMESTAMP
WHERE `id` = 4105 AND `status` = 1;

INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4110, 'Chat 集成', 'ai:application:query', 2, 13, 4000, 'chat-integration', 'ep:chat-line-round',
        'ai/chat-integration/index', 'AiChatIntegration', 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
