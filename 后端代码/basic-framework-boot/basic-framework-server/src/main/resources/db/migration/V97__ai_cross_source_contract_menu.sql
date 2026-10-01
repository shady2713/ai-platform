-- Y07：跨源合并结果对外入口的菜单与权限种子。
--
-- 本迁移**不新增任何数据表**：跨源执行台账（ai_cross_source_execution /
-- ai_cross_source_execution_source）由 V95 建立，本卡只在其上组装响应契约，
-- 运行期产物不落新表。刻意不为了"看起来有产出"造一张空表。
--
-- 权限码与 AiCrossSourceMergeController 的两个 @PreAuthorize 逐字对应：
--   ai:cross-source:query      —— 读取跨源合并结果（响应恒带完整性口径）
--   ai:cross-source:integrity  —— 只读授权完整性口径（响应中不含任何金额或条数）
--
-- 编号取 4133/4134：4000（AI 中台下）之下现有最大菜单为 4132（V89 流程编排）。
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4133, '跨源合并结果', 'ai:cross-source:query', 2, 19, 4000, 'cross-source', 'ep:share',
        'ai/cross-source/index', 'AiCrossSource', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0'),
       (4134, '跨源完整性口径', 'ai:cross-source:integrity', 3, 1, 4133, '', '', '', NULL, 0, b'1', b'1', b'1',
        '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
