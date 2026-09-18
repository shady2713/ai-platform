-- A09：授权页面落在 views/ai/authorization（应用接入与授权页面），
-- 与 V52 种子登记的组件路径 ai/grant/index 不一致；此处对齐菜单组件路径，
-- 权限码与菜单结构保持不变（仅修正前端组件指向）。

UPDATE `system_menu`
SET `component`     = 'ai/authorization/index',
    `component_name` = 'AiAuthorization'
WHERE `id` = 4020
  AND `deleted` = b'0';
