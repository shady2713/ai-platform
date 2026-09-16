package com.basicframework.module.infra.enums;

/**
 * Infra 操作日志枚举
 * 目的：统一管理，也减少 Service 里各种“复杂”字符串
 *
 * 文案只引用名称类字段，不 diff、不输出配置值或存储凭证，避免敏感信息进入操作日志。
 */
public interface LogRecordConstants {

    // ======================= INFRA_CONFIG 参数配置 =======================

    String INFRA_CONFIG_TYPE = "INFRA 参数配置";
    String INFRA_CONFIG_CREATE_SUB_TYPE = "创建参数配置";
    String INFRA_CONFIG_CREATE_SUCCESS = "创建了参数配置【{{#config.name}}】";
    String INFRA_CONFIG_UPDATE_SUB_TYPE = "更新参数配置";
    String INFRA_CONFIG_UPDATE_SUCCESS = "更新了参数配置【{{#config.name}}】";
    String INFRA_CONFIG_DELETE_SUB_TYPE = "删除参数配置";
    String INFRA_CONFIG_DELETE_SUCCESS = "删除了参数配置【{{#config.name}}】";

    // ======================= INFRA_FILE_CONFIG 文件配置 =======================

    String INFRA_FILE_CONFIG_TYPE = "INFRA 文件配置";
    String INFRA_FILE_CONFIG_CREATE_SUB_TYPE = "创建文件配置";
    String INFRA_FILE_CONFIG_CREATE_SUCCESS = "创建了文件配置【{{#fileConfig.name}}】";
    String INFRA_FILE_CONFIG_UPDATE_SUB_TYPE = "更新文件配置";
    String INFRA_FILE_CONFIG_UPDATE_SUCCESS = "更新了文件配置【{{#fileConfig.name}}】";
    String INFRA_FILE_CONFIG_UPDATE_MASTER_SUB_TYPE = "切换主文件配置";
    String INFRA_FILE_CONFIG_UPDATE_MASTER_SUCCESS = "将文件配置【{{#fileConfig.name}}】设为主配置";
    String INFRA_FILE_CONFIG_DELETE_SUB_TYPE = "删除文件配置";
    String INFRA_FILE_CONFIG_DELETE_SUCCESS = "删除了文件配置【{{#fileConfig.name}}】";

    // ======================= INFRA_JOB 定时任务 =======================

    String INFRA_JOB_TYPE = "INFRA 定时任务";
    String INFRA_JOB_CREATE_SUB_TYPE = "创建定时任务";
    String INFRA_JOB_CREATE_SUCCESS = "创建了定时任务【{{#job.name}}】";
    String INFRA_JOB_UPDATE_SUB_TYPE = "更新定时任务";
    String INFRA_JOB_UPDATE_SUCCESS = "更新了定时任务【{{#job.name}}】";
    String INFRA_JOB_UPDATE_STATUS_SUB_TYPE = "修改定时任务状态";
    String INFRA_JOB_UPDATE_STATUS_SUCCESS = "将定时任务【{{#job.name}}】的状态修改为【{{#statusName}}】";
    String INFRA_JOB_DELETE_SUB_TYPE = "删除定时任务";
    String INFRA_JOB_DELETE_SUCCESS = "删除了定时任务【{{#job.name}}】";
    String INFRA_JOB_TRIGGER_SUB_TYPE = "触发定时任务";
    String INFRA_JOB_TRIGGER_SUCCESS = "触发了定时任务【{{#job.name}}】";
}
