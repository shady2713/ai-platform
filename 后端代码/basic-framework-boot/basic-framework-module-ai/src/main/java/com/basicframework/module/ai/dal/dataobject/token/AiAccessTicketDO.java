package com.basicframework.module.ai.dal.dataobject.token;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/** AI 访问票据（A04）：只保存 token 摘要与裁剪后的范围快照。 */
@TableName("ai_access_ticket")
@KeySequence("ai_access_ticket_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiAccessTicketDO extends SoftDeletableDO {

    /** 状态：有效 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已撤销 */
    public static final String STATUS_REVOKED = "REVOKED";

    /** 票据编号 */
    @TableId
    private Long id;

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 外部用户标识（APP 主体为空串） */
    private String externalUserId;

    /** token 的 SHA-256 摘要；摘要不等于 token，但同样不进日志与响应 */
    @ToString.Exclude
    private String tokenDigest;

    /** 裁剪后的范围快照（JSON） */
    private String scopeSnapshot;

    /** 范围指纹（与 A03 判定一致） */
    private String scopeFingerprint;

    /** 签发时的授权版本 */
    private Long authzRevision;

    /** 到期时间 */
    private LocalDateTime expiresTime;

    /** 状态（ACTIVE/REVOKED） */
    private String status;

    /** 乐观锁版本 */
    private Integer version;
}
