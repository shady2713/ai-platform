package com.basicframework.module.system.enums;

/**
 * System 操作日志枚举
 * 目的：统一管理，也减少 Service 里各种“复杂”字符串
 *
 */
public interface LogRecordConstants {

    // ======================= SYSTEM_USER 用户 =======================

    String SYSTEM_USER_TYPE = "SYSTEM 用户";
    String SYSTEM_USER_CREATE_SUB_TYPE = "创建用户";
    String SYSTEM_USER_CREATE_SUCCESS = "创建了用户【{{#user.nickname}}】";
    String SYSTEM_USER_UPDATE_SUB_TYPE = "更新用户";
    String SYSTEM_USER_UPDATE_SUCCESS = "更新了用户【{{#user.nickname}}】: {_DIFF{#updateObj}}";
    String SYSTEM_USER_DELETE_SUB_TYPE = "删除用户";
    String SYSTEM_USER_DELETE_SUCCESS = "删除了用户【{{#user.nickname}}】";
    String SYSTEM_USER_UPDATE_PASSWORD_SUB_TYPE = "重置用户密码";
    String SYSTEM_USER_UPDATE_PASSWORD_SUCCESS = "重置了用户【{{#user.nickname}}】的密码";
    String SYSTEM_USER_UPDATE_OWN_PASSWORD_SUB_TYPE = "修改个人密码";
    String SYSTEM_USER_UPDATE_OWN_PASSWORD_SUCCESS = "用户【{{#user.nickname}}】修改了个人密码";
    String SYSTEM_USER_UPDATE_STATUS_SUB_TYPE = "修改用户状态";
    String SYSTEM_USER_UPDATE_STATUS_SUCCESS = "将用户【{{#user.nickname}}】的状态修改为【{{#statusName}}】";

    // ======================= SYSTEM_ROLE 角色 =======================

    String SYSTEM_ROLE_TYPE = "SYSTEM 角色";
    String SYSTEM_ROLE_CREATE_SUB_TYPE = "创建角色";
    String SYSTEM_ROLE_CREATE_SUCCESS = "创建了角色【{{#role.name}}】";
    String SYSTEM_ROLE_UPDATE_SUB_TYPE = "更新角色";
    String SYSTEM_ROLE_UPDATE_SUCCESS = "更新了角色【{{#role.name}}】: {_DIFF{#updateObj}}";
    String SYSTEM_ROLE_DELETE_SUB_TYPE = "删除角色";
    String SYSTEM_ROLE_DELETE_SUCCESS = "删除了角色【{{#role.name}}】";

    // ======================= SYSTEM_PERMISSION 权限 =======================

    String SYSTEM_PERMISSION_TYPE = "SYSTEM 权限";
    String SYSTEM_PERMISSION_ASSIGN_USER_ROLE_SUB_TYPE = "分配用户角色";
    String SYSTEM_PERMISSION_ASSIGN_USER_ROLE_SUCCESS = "修改了用户【{{#userId}}】的角色";
    String SYSTEM_PERMISSION_ASSIGN_ROLE_MENU_SUB_TYPE = "分配角色菜单";
    String SYSTEM_PERMISSION_ASSIGN_ROLE_MENU_SUCCESS = "修改了角色【{{#roleId}}】的菜单权限";
    String SYSTEM_PERMISSION_ASSIGN_ROLE_DATA_SCOPE_SUB_TYPE = "分配角色数据范围";
    String SYSTEM_PERMISSION_ASSIGN_ROLE_DATA_SCOPE_SUCCESS = "修改了角色【{{#roleId}}】的数据范围";
}
