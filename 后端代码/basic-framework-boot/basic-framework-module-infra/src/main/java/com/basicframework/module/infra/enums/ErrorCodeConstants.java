package com.basicframework.module.infra.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * Infra 错误码枚举类
 *
 * infra 系统，使用 1-001-000-000 段
 */
public interface ErrorCodeConstants {

    // ========== 参数配置 1-001-000-000 ==========
    ErrorCode CONFIG_NOT_EXISTS = new ErrorCode(1_001_000_001, "参数配置不存在");
    ErrorCode CONFIG_KEY_DUPLICATE = new ErrorCode(1_001_000_002, "参数配置 key 重复");
    ErrorCode CONFIG_CAN_NOT_DELETE_SYSTEM_TYPE = new ErrorCode(1_001_000_003, "不能删除类型为系统内置的参数配置");
    ErrorCode CONFIG_GET_VALUE_ERROR_IF_VISIBLE = new ErrorCode(1_001_000_004, "获取参数配置失败，原因：不允许获取不可见配置");

    // ========== 定时任务 1-001-001-000 ==========
    ErrorCode JOB_NOT_EXISTS = new ErrorCode(1_001_001_000, "定时任务不存在");
    ErrorCode JOB_HANDLER_EXISTS = new ErrorCode(1_001_001_001, "定时任务的处理器已经存在");
    ErrorCode JOB_CHANGE_STATUS_INVALID = new ErrorCode(1_001_001_002, "只允许修改为开启或者关闭状态");
    ErrorCode JOB_CHANGE_STATUS_EQUALS = new ErrorCode(1_001_001_003, "定时任务已经处于该状态，无需修改");
    ErrorCode JOB_UPDATE_ONLY_NORMAL_STATUS = new ErrorCode(1_001_001_004, "只有开启状态的任务，才可以修改");
    ErrorCode JOB_CRON_EXPRESSION_VALID = new ErrorCode(1_001_001_005, "CRON 表达式不正确");
    ErrorCode JOB_HANDLER_BEAN_NOT_EXISTS = new ErrorCode(1_001_001_006, "定时任务的处理器 Bean 不存在，注意 Bean 默认首字母小写");
    ErrorCode JOB_HANDLER_BEAN_TYPE_ERROR = new ErrorCode(1_001_001_007, "定时任务的处理器 Bean 类型不正确，未实现 JobHandler 接口");

    // ========= 文件相关 1-001-003-000 =================
    ErrorCode FILE_NOT_EXISTS = new ErrorCode(1_001_003_001, "文件不存在");
    ErrorCode FILE_IS_EMPTY = new ErrorCode(1_001_003_002, "文件为空");
    ErrorCode FILE_TYPE_NOT_ALLOWED = new ErrorCode(1_001_003_003, "不支持的文件类型");
    ErrorCode FILE_PATH_INVALID = new ErrorCode(1_001_003_004, "文件路径不合法");
    ErrorCode FILE_CLIENT_NOT_EXISTS = new ErrorCode(1_001_003_005, "文件客户端({}) 不存在");
    ErrorCode FILE_ARCHIVE_UNSAFE = new ErrorCode(1_001_003_006, "压缩包结构或展开规模不安全");
    ErrorCode FILE_METADATA_INVALID = new ErrorCode(1_001_003_007, "文件元数据不合法");
    ErrorCode FILE_UPLOAD_TOKEN_INVALID = new ErrorCode(1_001_003_008, "上传凭据无效、已过期或已使用");
    ErrorCode FILE_UPLOAD_OBJECT_INVALID = new ErrorCode(1_001_003_009, "上传对象不存在或与签发信息不一致");
    ErrorCode FILE_PRESIGNED_UPLOAD_REQUIRES_PRIVATE_STORAGE = new ErrorCode(1_001_003_010, "预签名上传必须使用私有对象存储配置");
    ErrorCode FILE_PRIVATE_READ_REQUIRES_PRIVATE_STORAGE = new ErrorCode(1_001_003_011, "私有文件必须使用受控读取的存储配置");

    // ========== 文件配置 1-001-006-000 ==========
    ErrorCode FILE_CONFIG_NOT_EXISTS = new ErrorCode(1_001_006_000, "文件配置不存在");
    ErrorCode FILE_CONFIG_DELETE_FAIL_MASTER = new ErrorCode(1_001_006_001, "该文件配置不允许删除，原因：它是主配置，删除会导致无法上传文件");
    ErrorCode FILE_CONFIG_IN_USE = new ErrorCode(1_001_006_002, "文件配置({})仍被文件或文件内容引用，无法删除");
}
