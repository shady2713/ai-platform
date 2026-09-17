package com.basicframework.module.ai.service.auth.dto;

import java.time.LocalDateTime;
import java.util.Set;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 换票结果（服务层 DTO）：**唯一**携带 token 明文的载体，返回一次后平台只留摘要。 */
@Data
@Accessors(chain = true)
public class AiTicketIssueDTO {

    /** 应用编号 */
    private Long applicationId;

    /** 主体类型 */
    private String subjectType;

    /** 外部用户标识 */
    private String externalUserId;

    /** 票据明文（32 字节随机值的 Base64URL 编码）；只在响应出现一次 */
    @ToString.Exclude
    private String token;

    /** 票据到期时间 */
    private LocalDateTime expiresTime;

    /** 裁剪后的组织白名单 */
    private Set<Long> organizationIds;

    /** 裁剪后的对象白名单 */
    private Set<String> resourceKeys;

    /** 范围来源 */
    private String scopeSource;

    /** 范围指纹：范围收窄后变化，历史产物需重新鉴权 */
    private String scopeFingerprint;
}
