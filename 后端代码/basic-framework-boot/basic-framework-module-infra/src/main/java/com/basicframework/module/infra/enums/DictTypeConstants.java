package com.basicframework.module.infra.enums;

/**
 * Infra 字典类型的枚举类
 *
 */
public interface DictTypeConstants {

    String USER_TYPE = "user_type"; // 用户类型（system 域字典，此处为跨域同源字面量副本：模块隔离禁止 infra 引用 system，属预期重复）

    String JOB_STATUS = "infra_job_status"; // 定时任务状态的枚举
    String JOB_LOG_STATUS = "infra_job_log_status"; // 定时任务日志状态的枚举

    String API_ERROR_LOG_PROCESS_STATUS = "infra_api_error_log_process_status"; // API 错误日志的处理状态的枚举

    String CONFIG_TYPE = "infra_config_type"; // 参数配置类型
    String BOOLEAN_STRING = "infra_boolean_string"; // Boolean 是否类型（本域字典；system 侧存有同源字面量副本，属预期重复）
}
