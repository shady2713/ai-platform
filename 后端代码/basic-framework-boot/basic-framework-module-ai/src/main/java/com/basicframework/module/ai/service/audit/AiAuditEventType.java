package com.basicframework.module.ai.service.audit;

/**
 * 审计事件类型（Q01）：覆盖"运行、工具动作、资源访问、文件与知识、运维诊断"五类。
 *
 * <p>类型是**稳定协议**（日志平台按它建告警与看板），新增类型必须同步
 * `docs/contracts/ai/audit-event.schema.json` 与 `docs/security/ai-audit-and-diagnostics.md`。
 */
public enum AiAuditEventType {

    /** 运行受理 */
    RUN_ACCEPTED,

    /** 运行终态（成功/失败/取消） */
    RUN_FINISHED,

    /** 工具动作（申请确认 / 确认 / 拒绝 / 执行结果） */
    TOOL_ACTION,

    /** 受控资源访问（数据查询、报表读取、知识检索） */
    RESOURCE_ACCESS,

    /** 文件读取/导出 */
    FILE_ACCESS,

    /** 运维诊断（管理员查看脱敏信息） */
    OPS_DIAGNOSTIC;

    /** 是否为已知类型（拒绝未知类型写入审计，避免日志平台建出脏指标）。 */
    public static boolean isKnown(String name) {
        if (name == null) {
            return false;
        }
        for (AiAuditEventType type : values()) {
            if (type.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
