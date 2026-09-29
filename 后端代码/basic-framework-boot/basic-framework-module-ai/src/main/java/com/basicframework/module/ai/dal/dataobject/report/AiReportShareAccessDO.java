package com.basicframework.module.ai.dal.dataobject.report;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * AI 报表分享访问审计（X11）：**只追加**（append-retention），每次读取（含拒绝）一条。
 *
 * <p>为什么每次读取都留痕：分享是授予者有意签发的可见权，"谁在什么时候读到了什么程度"
 * 是授予者可查的事实（{@code /ai/report/share/access/list}），也是防枚举与失权语义的证据。
 * 表内不含令牌摘要、报表规格与数据正文——只有主体、结论、稳定原因码与是否真的出库了内容。
 *
 * <p>口径：{@code outcome} 回答"这次读取被受理了吗"（降级态也被受理，HTTP 200）；
 * {@code contentAuthorized} 回答"内容真的出库了吗"；{@code reasonCode} 给出稳定原因
 * （完整成功为空，五种拒绝/降级原因见常量词表）。
 */
@TableName("ai_report_share_access")
@KeySequence("ai_report_share_access_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiReportShareAccessDO extends BaseDO {

    /** 结论：读取被受理（含降级态） */
    public static final String OUTCOME_GRANTED = "GRANTED";

    /** 结论：读取被拒绝（404，防枚举） */
    public static final String OUTCOME_DENIED = "DENIED";

    /** 原因码：凭据与当前主体不符（含凭据不存在——防枚举共用同一原因，不区分"错"与"无"） */
    public static final String REASON_SUBJECT_MISMATCH = "subject-mismatch";

    /** 原因码：分享已被授予者撤销 */
    public static final String REASON_REVOKED = "revoked";

    /** 原因码：分享已过期（读取时惰性物化为 EXPIRED） */
    public static final String REASON_EXPIRED = "expired";

    /** 原因码：授予者主体已停用（离职默认拒绝，不因人走而留下永久可见入口） */
    public static final String REASON_GRANTOR_UNAVAILABLE = "grantor-unavailable";

    /** 原因码：接收者当前源权限无法覆盖分享时的范围（降级态：只给可见性，不给内容） */
    public static final String REASON_SCOPE_UNCOVERED = "scope-uncovered";

    /** 访问记录编号 */
    @TableId
    private Long id;

    /** 分享编号（凭据无法定位分享时为空） */
    private Long shareId;

    /** 报表编号（同上） */
    private Long reportId;

    /** 应用编号（有分享行时为分享所属应用，否则为访问主体所属应用） */
    private Long applicationId;

    /** 访问者主体类型 */
    private String subjectType;

    /** 访问者外部用户标识 */
    private String externalUserId;

    /** 结论（GRANTED/DENIED） */
    private String outcome;

    /** 稳定原因码（完整成功为空） */
    private String reasonCode;

    /** 本次是否真的出库了报表内容 */
    private Boolean contentAuthorized;
}
