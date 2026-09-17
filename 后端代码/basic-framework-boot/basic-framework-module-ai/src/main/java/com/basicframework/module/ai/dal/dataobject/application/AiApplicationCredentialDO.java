package com.basicframework.module.ai.dal.dataobject.application;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * AI 应用客户端凭据（A01）：**只保存摘要**，明文只在创建/轮换响应里出现一次。
 *
 * <p>状态机：{@link #STATUS_ACTIVE} 可换票；{@link #STATUS_REVOKED} 立即失效（吊销或轮换自动执行）。
 */
@TableName("ai_application_credential")
@KeySequence("ai_application_credential_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiApplicationCredentialDO extends SoftDeletableDO {

    /** 状态：可用 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 状态：已吊销（立即失效，不参与校验） */
    public static final String STATUS_REVOKED = "REVOKED";

    /** 凭据编号 */
    @TableId
    private Long id;

    /** 应用编号 */
    private Long applicationId;

    /** 客户端秘密的 SHA-256 摘要（十六进制）；摘要不等于秘密，但同样不进日志与响应 */
    @ToString.Exclude
    private String secretDigest;

    /** 状态（ACTIVE/REVOKED） */
    private String status;

    /** 吊销时间 */
    private java.time.LocalDateTime revokedTime;
}
