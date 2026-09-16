package com.basicframework.module.system.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * System 错误码枚举类
 *
 * system 系统，使用 1-002-000-000 段
 */
public interface ErrorCodeConstants {

    // ========== AUTH 模块 1-002-000-000 ==========
    ErrorCode AUTH_LOGIN_BAD_CREDENTIALS = new ErrorCode(1_002_000_000, "登录失败，账号密码不正确");
    ErrorCode AUTH_LOGIN_USER_DISABLED = new ErrorCode(1_002_000_001, "登录失败，账号被禁用");
    ErrorCode AUTH_LOGIN_CAPTCHA_CODE_ERROR = new ErrorCode(1_002_000_004, "验证码不正确，原因：{}");
    ErrorCode AUTH_REGISTER_CAPTCHA_CODE_ERROR = new ErrorCode(1_002_000_008, "验证码不正确，原因：{}");
    ErrorCode AUTH_PASSWORD_EXPIRED = new ErrorCode(1_002_000_019, "密码已过期，请修改密码后重新登录");
    ErrorCode AUTH_PASSWORD_SAME_AS_OLD = new ErrorCode(1_002_000_020, "新密码不能与原密码相同");
    ErrorCode AUTH_PASSWORD_CHANGE_NOT_REQUIRED = new ErrorCode(1_002_000_021, "当前账号无需强制修改密码");

    // ========== 菜单模块 1-002-001-000 ==========
    ErrorCode MENU_NAME_DUPLICATE = new ErrorCode(1_002_001_000, "已经存在该名字的菜单");
    ErrorCode MENU_PARENT_NOT_EXISTS = new ErrorCode(1_002_001_001, "父菜单不存在");
    ErrorCode MENU_PARENT_ERROR = new ErrorCode(1_002_001_002, "不能设置自己为父菜单");
    ErrorCode MENU_NOT_EXISTS = new ErrorCode(1_002_001_003, "菜单不存在");
    ErrorCode MENU_EXISTS_CHILDREN = new ErrorCode(1_002_001_004, "存在子菜单，无法删除");
    ErrorCode MENU_PARENT_NOT_DIR_OR_MENU = new ErrorCode(1_002_001_005, "父菜单的类型必须是目录或者菜单");
    ErrorCode MENU_COMPONENT_NAME_DUPLICATE = new ErrorCode(1_002_001_006, "已经存在该组件名的菜单");
    ErrorCode MENU_PARENT_IS_CHILD = new ErrorCode(1_002_001_007, "不能设置自己的子菜单为父菜单");
    ErrorCode MENU_PARENT_CYCLE = new ErrorCode(1_002_001_008, "父菜单层级存在循环引用");
    ErrorCode MENU_BUTTON_EXISTS_CHILDREN = new ErrorCode(1_002_001_009, "存在子菜单，不能修改为按钮");
    ErrorCode MENU_IS_DISABLE = new ErrorCode(1_002_001_010, "名字为【{}】的菜单已被禁用");

    // ========== 角色模块 1-002-002-000 ==========
    ErrorCode ROLE_NOT_EXISTS = new ErrorCode(1_002_002_000, "角色不存在");
    ErrorCode ROLE_NAME_DUPLICATE = new ErrorCode(1_002_002_001, "已经存在名为【{}】的角色");
    ErrorCode ROLE_CODE_DUPLICATE = new ErrorCode(1_002_002_002, "已经存在标识为【{}】的角色");
    ErrorCode ROLE_CAN_NOT_UPDATE_SYSTEM_TYPE_ROLE = new ErrorCode(1_002_002_003, "不能操作类型为系统内置的角色");
    ErrorCode ROLE_IS_DISABLE = new ErrorCode(1_002_002_004, "名字为【{}】的角色已被禁用");
    ErrorCode ROLE_ADMIN_CODE_ERROR = new ErrorCode(1_002_002_005, "标识【{}】不能使用");
    ErrorCode ROLE_IS_REFERENCED = new ErrorCode(1_002_002_006, "角色【{}】已被用户引用，不允许禁用");
    ErrorCode ROLE_DATA_SCOPE_INVALID = new ErrorCode(1_002_002_007, "角色数据范围({})无效");
    ErrorCode ROLE_SUPER_ADMIN_OPERATION_FORBIDDEN = new ErrorCode(1_002_002_008, "只有超级管理员可以变更超级管理员角色");

    // ========== 用户模块 1-002-003-000 ==========
    ErrorCode USER_USERNAME_EXISTS = new ErrorCode(1_002_003_000, "用户账号已经存在");
    ErrorCode USER_MOBILE_EXISTS = new ErrorCode(1_002_003_001, "手机号已经存在");
    ErrorCode USER_EMAIL_EXISTS = new ErrorCode(1_002_003_002, "邮箱已经存在");
    ErrorCode USER_NOT_EXISTS = new ErrorCode(1_002_003_003, "用户不存在");
    ErrorCode USER_IMPORT_LIST_IS_EMPTY = new ErrorCode(1_002_003_004, "导入用户数据不能为空！");
    ErrorCode USER_PASSWORD_FAILED = new ErrorCode(1_002_003_005, "用户密码校验失败");
    ErrorCode USER_IS_DISABLE = new ErrorCode(1_002_003_006, "名字为【{}】的用户已被禁用");
    ErrorCode USER_PASSWORD_POLICY_VIOLATION = new ErrorCode(1_002_003_007, "密码不符合安全策略，请使用至少 15 个字符并避开弱密码、账号名或平台名");
    ErrorCode USER_SELF_OPERATION_FORBIDDEN = new ErrorCode(1_002_003_008, "不能删除或禁用当前登录账号");

    // ========== 部门模块 1-002-004-000 ==========
    ErrorCode DEPT_NAME_DUPLICATE = new ErrorCode(1_002_004_000, "已经存在该名字的部门");
    ErrorCode DEPT_PARENT_NOT_EXISTS = new ErrorCode(1_002_004_001, "父级部门不存在");
    ErrorCode DEPT_NOT_EXISTS = new ErrorCode(1_002_004_002, "当前部门不存在");
    ErrorCode DEPT_EXISTS_CHILDREN = new ErrorCode(1_002_004_003, "存在子部门，无法删除");
    ErrorCode DEPT_PARENT_ERROR = new ErrorCode(1_002_004_004, "不能设置自己为父部门");
    ErrorCode DEPT_EXISTS_USER = new ErrorCode(1_002_004_005, "部门下存在用户，无法删除");
    ErrorCode DEPT_NOT_ENABLE = new ErrorCode(1_002_004_006, "部门({})不处于开启状态，不允许选择");
    ErrorCode DEPT_PARENT_IS_CHILD = new ErrorCode(1_002_004_007, "不能设置自己的子部门为父部门");
    ErrorCode DEPT_EXISTS_ROLE_DATA_SCOPE = new ErrorCode(1_002_004_008, "部门仍被角色数据范围引用，无法删除");
    ErrorCode DEPT_PARENT_CYCLE = new ErrorCode(1_002_004_009, "父部门层级存在循环引用");

    // ========== 岗位模块 1-002-005-000 ==========
    ErrorCode POST_NOT_EXISTS = new ErrorCode(1_002_005_000, "当前岗位不存在");
    ErrorCode POST_NOT_ENABLE = new ErrorCode(1_002_005_001, "岗位({}) 不处于开启状态，不允许选择");
    ErrorCode POST_NAME_DUPLICATE = new ErrorCode(1_002_005_002, "已经存在该名字的岗位");
    ErrorCode POST_CODE_DUPLICATE = new ErrorCode(1_002_005_003, "已经存在该标识的岗位");
    ErrorCode POST_IS_REFERENCED = new ErrorCode(1_002_005_004, "岗位【{}】已被用户引用，不允许禁用");

    // ========== 字典类型 1-002-006-000 ==========
    ErrorCode DICT_TYPE_NOT_EXISTS = new ErrorCode(1_002_006_001, "当前字典类型不存在");
    ErrorCode DICT_TYPE_NOT_ENABLE = new ErrorCode(1_002_006_002, "字典类型不处于开启状态，不允许选择");
    ErrorCode DICT_TYPE_NAME_DUPLICATE = new ErrorCode(1_002_006_003, "已经存在该名字的字典类型");
    ErrorCode DICT_TYPE_TYPE_DUPLICATE = new ErrorCode(1_002_006_004, "已经存在该类型的字典类型");
    ErrorCode DICT_TYPE_HAS_CHILDREN = new ErrorCode(1_002_006_005, "无法删除，该字典类型还有字典数据");
    ErrorCode DICT_TYPE_CHANGE_HAS_CHILDREN = new ErrorCode(1_002_006_006, "字典类型已有字典数据，不允许修改类型标识");

    // ========== 字典数据 1-002-007-000 ==========
    ErrorCode DICT_DATA_NOT_EXISTS = new ErrorCode(1_002_007_001, "当前字典数据不存在");
    ErrorCode DICT_DATA_NOT_ENABLE = new ErrorCode(1_002_007_002, "字典数据({})不处于开启状态，不允许选择");
    ErrorCode DICT_DATA_VALUE_DUPLICATE = new ErrorCode(1_002_007_003, "已经存在该值的字典数据");

    // ========== 通知公告 1-002-008-000 ==========
    ErrorCode NOTICE_NOT_EXISTS = new ErrorCode(1_002_008_001, "当前通知公告不存在");

    // ========== IP 地区 1-002-009-000 ==========
    ErrorCode AREA_CHINA_NOT_EXISTS = new ErrorCode(1_002_009_000, "获取不到中国地区数据");

    // ========== 短信渠道 1-002-011-000 ==========
    ErrorCode SMS_CHANNEL_NOT_EXISTS = new ErrorCode(1_002_011_000, "短信渠道不存在");
    ErrorCode SMS_CHANNEL_DISABLE = new ErrorCode(1_002_011_001, "短信渠道不处于开启状态，不允许选择");
    ErrorCode SMS_CHANNEL_HAS_CHILDREN = new ErrorCode(1_002_011_002, "无法删除，该短信渠道还有短信模板");
    ErrorCode SMS_CHANNEL_CODE_DUPLICATE = new ErrorCode(1_002_011_003, "已存在【{}】短信渠道");
    ErrorCode SMS_CLIENT_NOT_EXISTS = new ErrorCode(1_002_011_004, "短信客户端({}) 不存在");
    ErrorCode SMS_CHANNEL_CHANGED = new ErrorCode(1_002_011_005, "短信渠道配置已变更，请重试");

    // ========== 短信模板 1-002-012-000 ==========
    ErrorCode SMS_TEMPLATE_NOT_EXISTS = new ErrorCode(1_002_012_000, "短信模板不存在");
    ErrorCode SMS_TEMPLATE_CODE_DUPLICATE = new ErrorCode(1_002_012_001, "已经存在编码为【{}】的短信模板");
    ErrorCode SMS_TEMPLATE_API_ERROR = new ErrorCode(1_002_012_002, "短信 API 模板调用失败，请稍后重试");
    ErrorCode SMS_TEMPLATE_API_AUDIT_CHECKING = new ErrorCode(1_002_012_003, "短信 API 模版无法使用，原因：审批中");
    ErrorCode SMS_TEMPLATE_API_AUDIT_FAIL = new ErrorCode(1_002_012_004, "短信 API 模版无法使用，原因：审批不通过，{}");
    ErrorCode SMS_TEMPLATE_API_NOT_EXISTS = new ErrorCode(1_002_012_005, "短信 API 模版无法使用，原因：模版不存在");

    // ========== 短信发送 1-002-013-000 ==========
    ErrorCode SMS_SEND_MOBILE_NOT_EXISTS = new ErrorCode(1_002_013_000, "手机号不存在");
    ErrorCode SMS_SEND_MOBILE_TEMPLATE_PARAM_MISS = new ErrorCode(1_002_013_001, "模板参数({})缺失");
    ErrorCode SMS_SEND_TEMPLATE_NOT_EXISTS = new ErrorCode(1_002_013_002, "短信模板不存在");
    ErrorCode SMS_CALLBACK_UNAUTHORIZED = new ErrorCode(1_002_013_003, "短信回调认证失败");
    ErrorCode SMS_CALLBACK_PAYLOAD_INVALID = new ErrorCode(1_002_013_004, "短信回调内容无效");

    // ========== 短信验证码 1-002-014-000 ==========
    ErrorCode SMS_CODE_NOT_EXISTS = new ErrorCode(1_002_014_000, "验证码不存在");
    ErrorCode SMS_CODE_EXPIRED = new ErrorCode(1_002_014_001, "验证码已过期");
    ErrorCode SMS_CODE_USED = new ErrorCode(1_002_014_002, "验证码已使用");
    ErrorCode SMS_CODE_SCENE_NOT_EXISTS = new ErrorCode(1_002_014_003, "验证码场景({}) 不存在");
    ErrorCode SMS_CODE_EXCEED_SEND_MAXIMUM_QUANTITY_PER_DAY = new ErrorCode(1_002_014_004, "超过每日短信发送数量");
    ErrorCode SMS_CODE_SEND_TOO_FAST = new ErrorCode(1_002_014_005, "短信发送过于频繁");

    // ========== 会话令牌 1-002-021-000 =========
    ErrorCode SESSION_REFRESH_TOKEN_INVALID = new ErrorCode(1_002_021_003, "无效的刷新令牌");
    ErrorCode SESSION_REFRESH_TOKEN_EXPIRED = new ErrorCode(1_002_021_005, "刷新令牌已过期");
    ErrorCode SESSION_ACCESS_TOKEN_NOT_EXISTS = new ErrorCode(1_002_021_006, "访问令牌不存在");
    ErrorCode SESSION_ACCESS_TOKEN_EXPIRED = new ErrorCode(1_002_021_007, "访问令牌已过期");

    // ========== 站内信模版 1-002-026-000 ==========
    ErrorCode NOTIFY_TEMPLATE_NOT_EXISTS = new ErrorCode(1_002_026_000, "站内信模版不存在");
    ErrorCode NOTIFY_TEMPLATE_CODE_DUPLICATE = new ErrorCode(1_002_026_001, "已经存在编码为【{}】的站内信模板");

    // ========== 站内信发送 1-002-028-000 ==========
    ErrorCode NOTIFY_SEND_TEMPLATE_PARAM_MISS = new ErrorCode(1_002_028_000, "模板参数({})缺失");
    ErrorCode NOTIFY_SEND_USER_TYPE_INVALID = new ErrorCode(1_002_028_001, "站内信接收用户类型不受支持");
}
