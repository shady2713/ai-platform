package com.basicframework.module.ai.service.audit;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 审计事件（Q01）：**只记录可对账的结构化事实**。
 *
 * <p>设计取舍（AT-058）：审计要能回答"谁在什么资源上做了什么、结果如何、花了多久"，
 * 而不是"用户问了什么"。因此本记录**没有自由文本字段**：
 *
 * <ul>
 *   <li>主体用不透明标识（应用 + 外部主体标识的摘要，不是姓名/邮箱）；</li>
 *   <li>资源用类型 + 不透明标识（与开放协议的前缀一致），不含标题与正文；</li>
 *   <li>结果只有稳定错误码（{@code outcomeCode}）与耗时，不含异常正文、
 *       SQL 语句与参数、模型输入输出；</li>
 *   <li>没有"问题正文/提示词/片段"这类字段——需要正文的场景走受权接口，不进日志。</li>
 * </ul>
 *
 * <p>字段顺序即结构化日志的键顺序，便于日志平台按字段建索引。
 */
public record AiAuditEvent(
        /** 事件类型（见 {@link AiAuditEventType}） */
        String eventType,

        /** 运行标识（含任务/步骤时形如 run_xxx#step） */
        String runRef,

        /** 主体标识（应用编号 + 主体标识摘要，可对账到主体但不含身份信息） */
        String subjectRef,

        /** 资源引用（类型:标识，如 REPORT:rpt_ab12） */
        List<String> resourceRefs,

        /** 动作（与 scope 目录的动作词表同口径，如 READ/EXECUTE/EXPORT） */
        String action,

        /** 结果码（成功为 AI_OK，失败为稳定错误码常量名） */
        String outcomeCode,

        /** 耗时（毫秒；未知为 -1） */
        long durationMs,

        /** 发生时间 */
        LocalDateTime occurredAt) {

    /** 成功结果码（不写"成功"这种自由文本，便于统计）。 */
    public static final String OUTCOME_OK = "AI_OK";

    /** 未知耗时。 */
    public static final long DURATION_UNKNOWN = -1L;

    /** 结构化日志行（键值对，供日志平台解析；不含任何自由文本）。 */
    public String toLogLine() {
        return "event=" + eventType
                + " run=" + orDash(runRef)
                + " subject=" + orDash(subjectRef)
                + " resources=" + String.join(",", resourceRefs)
                + " action=" + orDash(action)
                + " outcome=" + orDash(outcomeCode)
                + " durationMs=" + durationMs
                + " at=" + occurredAt;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
