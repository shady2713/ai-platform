package com.basicframework.module.ai.dal.dataobject.report;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * AI 报表受控分享（X11）：**凭据摘要 + 固定版本 + 可撤销**的可见权凭据。
 *
 * <p>三条不变量：
 * <ul>
 *   <li><b>凭据只存摘要</b>：明文令牌（32 字节 SecureRandom → Base64URL）仅在创建响应出现一次，
 *       库里只有它的 SHA-256 摘要（{@code uk_ai_report_share_token}），摘要不可反推；</li>
 *   <li><b>版本在创建时固定</b>：{@code version_no} 是签发时刻的版本，报表新增版本不改变分享内容；
 *       撤销后连这个版本也不再可读（历史版本同样拒绝）；</li>
 *   <li><b>状态即语义</b>：ACTIVE 可读、REVOKED 被授予者撤销（立即生效）、EXPIRED 已过期
 *       （读取时惰性物化，不设常驻扫描任务——X10 的教训：无界扫描伤请求延迟）。</li>
 * </ul>
 *
 * <p>分享只授予"可见权"，不授予源数据读取权：内容出库前还要按接收者自己的 A03 授权逐项复核
 * （见 {@code AiReportShareServiceImpl}）。同报表 + 同接收者是否已有 ACTIVE 分享由服务层判定
 * （撤销后允许重新分享，因此不能用含状态列的唯一索引表达）。
 */
@TableName("ai_report_share")
@KeySequence("ai_report_share_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiReportShareDO extends SoftDeletableDO {

    /** 状态：生效中（可读） */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已被授予者撤销（立即生效，幂等重放按成功返回） */
    public static final String STATUS_REVOKED = "REVOKED";

    /** 状态：已过期（读取时惰性物化） */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 分享编号 */
    @TableId
    private Long id;

    /** 分享凭据的 SHA-256 摘要（小写十六进制；明文令牌不落库；toString 不回显） */
    @ToString.Exclude
    private String tokenHash;

    /** 报表编号 */
    private Long reportId;

    /** 分享时固定的版本号 */
    private Integer versionNo;

    /** 所属应用编号 */
    private Long applicationId;

    /** 授予者主体类型（固定 USER） */
    private String grantorSubjectType;

    /** 授予者外部用户标识（所有者） */
    private String grantorExternalUserId;

    /** 接收者主体类型（固定 USER） */
    private String granteeSubjectType;

    /** 接收者外部用户标识 */
    private String granteeExternalUserId;

    /** 接收者显示名（创建时快照） */
    private String granteeDisplayName;

    /** 状态（ACTIVE/REVOKED/EXPIRED） */
    private String status;

    /** 过期时间（空为长期有效） */
    private LocalDateTime expiresTime;

    /** 乐观锁版本（撤销 CAS 使用） */
    private Integer version;
}
