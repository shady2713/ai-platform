package com.basicframework.module.ai.dal.dataobject.report;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 报表刷新尝试（R06）：每次刷新一条记录，成功带数据、失败带稳定原因。
 *
 * <p>为什么尝试记录要存数据：可刷新版本按 R04 口径不存数据，界面要展示的"上一次结果"就是
 * 最近一次成功刷新的绑定数据；它同样受当前 ACL 约束（读取时逐项复核范围指纹，AT-048）。
 *
 * <p>行数据不进 {@code toString()}（避免把业务数据带进日志）。
 */
@TableName("ai_report_refresh")
@KeySequence("ai_report_refresh_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiReportRefreshDO extends SoftDeletableDO {

    /** 结果：已刷新（产生新版本）。 */
    public static final String STATUS_OK = "OK";

    /** 结果：数据未变化（不产生新版本，重复刷新幂等）。 */
    public static final String STATUS_UNCHANGED = "UNCHANGED";

    /** 结果：失败（保留旧结果，原因码见 {@code reason}）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 尝试编号 */
    @TableId
    private Long id;

    /** 报表编号 */
    private Long reportId;

    /** 本次刷新基于的版本号 */
    private Integer baseVersionNo;

    /** 本次刷新产生的版本号（OK 时存在） */
    private Integer resultVersionNo;

    /** 结果（OK/UNCHANGED/FAILED） */
    private String status;

    /** 稳定原因码（失败为平台错误码；未变化为 UNCHANGED） */
    private String reason;

    /** 尝试时间（成功时即本次数据的截至时间） */
    private java.time.LocalDateTime asOf;

    /** 数据完整性（COMPLETE/PARTIAL） */
    private String completeness;

    /** 成功刷新时的绑定数据（读取时按当前 ACL 复核） */
    private String dataJson;

    /** 本次刷新引用的资源依赖（A03 词表） */
    private String sourcesJson;

    /** 逐项资源依赖的 A03 范围指纹（读取旧结果时复核） */
    private String scopeRefsJson;

    /** 本次刷新的授权范围指纹 */
    private String scopeFingerprint;

    /** 乐观锁版本 */
    private Integer version;
}
