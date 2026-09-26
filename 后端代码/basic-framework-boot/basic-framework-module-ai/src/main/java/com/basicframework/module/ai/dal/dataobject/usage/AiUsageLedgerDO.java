package com.basicframework.module.ai.dal.dataobject.usage;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 用量账本（Q02）：**一次真实上游调用一条**。
 *
 * <p>计量来源必须如实标注（AT-060）：{@link #usageSource} 为 REPORTED（上游报告）、
 * ESTIMATED（平台估算）或 UNKNOWN（未知）；token 未知时留空，绝不写 0 冒充实测。
 * 表内只有计量元数据：不含提示词、响应正文、端点地址与密钥。
 */
@TableName("ai_usage_ledger")
@KeySequence("ai_usage_ledger_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(
        callSuper = true,
        exclude = {"inputTokens", "outputTokens"})
@Accessors(chain = true)
public class AiUsageLedgerDO extends BaseDO {

    /** 计量来源：上游报告 */
    public static final String SOURCE_REPORTED = "REPORTED";

    /** 计量来源：平台估算 */
    public static final String SOURCE_ESTIMATED = "ESTIMATED";

    /** 计量来源：未知（不写 0） */
    public static final String SOURCE_UNKNOWN = "UNKNOWN";

    /** 调用结果：成功 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 调用结果：失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 调用结果：取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 记录编号 */
    @TableId
    private Long id;

    /** 调用标识（一次真实上游调用一个；重复写入去重） */
    private String invocationId;

    /** 运行编号（与 task 二选一） */
    private Long runId;

    /** 任务编号（与 run 二选一） */
    private Long taskId;

    /** 应用编号 */
    private Long applicationId;

    /** 服务编号 */
    private Long serviceId;

    /** 主体标识（应用编号 + 主体摘要，不含身份信息） */
    private String subjectRef;

    /** 模型标识（非秘密配置） */
    private String modelRef;

    /** 模型配置修订号 */
    private Integer modelRevision;

    /** 端点引用（编号/别名；不是地址或密钥） */
    private String endpointRef;

    /** 输入 token（未知为空） */
    private Long inputTokens;

    /** 输出 token（未知为空） */
    private Long outputTokens;

    /** 计量来源（REPORTED/ESTIMATED/UNKNOWN） */
    private String usageSource;

    /** 上游耗时（毫秒） */
    private Integer durationMs;

    /** 调用结果（SUCCEEDED/FAILED/CANCELLED） */
    private String status;

    /** 发生时间 */
    private LocalDateTime occurredAt;
}
