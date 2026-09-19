package com.basicframework.module.ai.dal.dataobject.run;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 运行（O02）：受理时冻结发布版本与端点配置版本，运行链路只用快照。
 *
 * <p>{@code inputDigest} 是受理请求的摘要（不含 traceId 与 token），幂等判定以它为准：
 * 同键同摘要复用原 run，同键异摘要返回 409。资源授权与停用状态不进入 run 行——
 * 它们始终在每次运行时按当前值判定（固定版本不是权限副本）。
 */
@TableName("ai_run")
@KeySequence("ai_run_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiRunDO extends SoftDeletableDO {

    /** 状态：已受理（尚未开始执行） */
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    /** 状态：执行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 状态：成功终态 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 状态：失败终态 */
    public static final String STATUS_FAILED = "FAILED";

    /** 状态：已取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 运行编号 */
    @TableId
    private Long id;

    /** 运行业务键（run_ 前缀，主体内唯一） */
    private String runKey;

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 可信外部用户标识 */
    private String externalUserId;

    /** 会话编号 */
    private Long conversationId;

    /** 服务编号 */
    private Long serviceId;

    /** 受理时固定的发布版本编号 */
    private Long releaseId;

    /** 受理时固定的模型端点编号 */
    private Long modelEndpointId;

    /** 受理时固定的端点配置版本 */
    private Integer endpointConfigRevision;

    /** 受理时固定的发布内容摘要 */
    private String contentHash;

    /** 请求摘要（幂等判定；不含 traceId 与 token） */
    private String inputDigest;

    /** 受理时声明的数据分级（执行阶段的外发策略按它判定） */
    private String dataLevel;

    /** 状态（ACCEPTED/RUNNING/SUCCEEDED/FAILED/CANCELLED） */
    private String status;

    /** 已执行步数（有界执行） */
    private Integer stepCount;

    /** 乐观锁版本 */
    private Integer version;
}
