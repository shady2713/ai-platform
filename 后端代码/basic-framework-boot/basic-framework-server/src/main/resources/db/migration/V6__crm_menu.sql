-- V6：crm 客户管理菜单与权限种子（场景 2 演练模块）
-- 权限码与 CustomerController 的 @PreAuthorize('crm:customer:*') 一一对应，
-- 缺此种子时新权限点将全部被安全过滤链拒绝。

-- 1. 菜单与按钮权限点
-- 菜单 id 取 3000 段：现存菜单最大 id=2739（2026-08 核对），3000 段空闲；
-- type：1 目录 / 2 菜单 / 3 按钮；permission 仅按钮与查询承载
INSERT INTO `system_menu`
    (`id`, `name`, `permission`, `type`, `sort`, `parent_id`, `path`,
     `icon`, `component`, `component_name`, `status`, `visible`,
     `keep_alive`, `always_show`, `creator`, `create_time`, `updater`,
     `update_time`, `deleted`)
VALUES
    (3000, '客户管理', '', 1, 30, 0, '/crm', 'ep:user', '', NULL,
     0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
    (3001, '客户档案', 'crm:customer:query', 2, 1, 3000, 'customer',
     'ep:avatar', 'crm/customer/index', 'CrmCustomer', 0, b'1', b'1', b'1',
     '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
    (3002, '客户新增', 'crm:customer:create', 3, 1, 3001, '', '', '', NULL,
     0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
    (3003, '客户修改', 'crm:customer:update', 3, 2, 3001, '', '', '', NULL,
     0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
    (3004, '客户删除', 'crm:customer:delete', 3, 3, 3001, '', '', '', NULL,
     0, b'1', b'1', b'1', '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');

-- 2. 超管角色（role_id=1）授权
-- 主键不自增指定（uk_role_id_menu_id 已由 V3 建立，新 menu_id 无冲突）
INSERT INTO `system_role_menu`
    (`role_id`, `menu_id`, `creator`, `create_time`, `updater`, `update_time`, `deleted`)
VALUES
    (1, 3000, '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
    (1, 3001, '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
    (1, 3002, '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
    (1, 3003, '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0'),
    (1, 3004, '1', CURRENT_TIMESTAMP, '1', CURRENT_TIMESTAMP, b'0');