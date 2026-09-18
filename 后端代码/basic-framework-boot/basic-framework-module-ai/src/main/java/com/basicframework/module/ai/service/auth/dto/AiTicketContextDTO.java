package com.basicframework.module.ai.service.auth.dto;

import java.util.Set;
import lombok.Data;
import lombok.experimental.Accessors;

/** 票据校验结果（服务端上下文）：只包含服务端建立的事实与裁剪后的范围。 */
@Data
@Accessors(chain = true)
public class AiTicketContextDTO {

    /** 票据编号 */
    private Long ticketId;

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型 */
    private String subjectType;

    /** 外部用户标识 */
    private String externalUserId;

    /** 组织白名单 */
    private Set<Long> organizationIds;

    /** 对象白名单 */
    private Set<String> resourceKeys;

    /** 范围指纹 */
    private String scopeFingerprint;

    /** 授权版本 */
    private Long authzRevision;

    /** 票据到期时间（会话校验结果的过期时间） */
    private java.time.LocalDateTime expiresTime;
}
