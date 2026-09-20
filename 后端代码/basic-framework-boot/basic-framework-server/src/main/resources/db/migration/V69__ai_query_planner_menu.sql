-- D05：自然语言查询规划（模型输出 PLAN/CLARIFICATION，平台侧二次校验）。
-- 本卡不新增数据表：计划是运行期产物（带计划哈希，由调用方/运行记录持有），
-- 规划器本身无状态；这里只落地控制面权限点。

-- AI 查询规划菜单与权限点（4070-4071，挂在 4000 AI 中台下）
INSERT INTO `system_menu`
(`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
 `icon`, `component`, `component_name`, `status`, `visible`,
 `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
 `update_time`, `deleted`)
VALUES (4070, 'AI 查询计划', 'ai:query:plan', 2, 8, 4000, 'query', 'ep:search',
        'ai/query/index', 'AiQuery', 0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1',
        CURRENT_TIMESTAMP, b'0'),
       (4071, '数据集摘要', 'ai:query:summary', 3, 1, 4070, '', '', '', NULL, 0, b'1', b'1', b'1', '1',
        CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');
