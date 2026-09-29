package com.basicframework.module.ai.service.report.share.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 创建报表分享结果（服务层 DTO）：明文令牌**只在这里出现一次**（创建响应），之后平台只认其摘要，
 * 任何查询/审计/日志都不再返回明文。
 */
@Data
@Accessors(chain = true)
public class AiReportShareCreateResultDTO {

    /** 分享编号（授予者后续撤销与查审计使用） */
    private Long shareId;

    /** 明文分享令牌（一次性返回；接收者用它调用 /ai/report/share/read；toString 不回显） */
    @ToString.Exclude
    private String token;

    /** 分享时固定的版本号 */
    private Integer versionNo;

    /** 接收者外部用户标识 */
    private String granteeExternalUserId;

    /** 接收者显示名（创建时快照） */
    private String granteeDisplayName;

    /** 过期时间（空为长期有效） */
    private LocalDateTime expiresTime;
}
