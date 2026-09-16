package com.basicframework.module.system.enums;

/**
 * System 字典类型枚举类
 */
public interface DictTypeConstants {

    String USER_TYPE = "user_type"; // 用户类型（本域字典；infra 侧存有同源字面量副本，模块隔离禁止 infra 引用 system，属预期重复）
    String COMMON_STATUS = "common_status";
    String BOOLEAN_STRING = "infra_boolean_string"; // Boolean 是否类型（infra 域字典，此处为跨域同源字面量副本，属预期重复）

    // ========== SYSTEM ==========

    String USER_SEX = "system_user_sex";
    String DATA_SCOPE = "system_data_scope";
    String SYSTEM_NOTICE_TYPE = "system_notice_type";

    String LOGIN_TYPE = "system_login_type";
    String LOGIN_RESULT = "system_login_result";

    String SMS_CHANNEL_CODE = "system_sms_channel_code";
    String SMS_TEMPLATE_TYPE = "system_sms_template_type";
    String SMS_SEND_STATUS = "system_sms_send_status";
    String SMS_RECEIVE_STATUS = "system_sms_receive_status";
}
