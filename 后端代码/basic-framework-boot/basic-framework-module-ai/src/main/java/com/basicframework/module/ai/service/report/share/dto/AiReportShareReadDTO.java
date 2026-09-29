package com.basicframework.module.ai.service.report.share.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 按令牌读取分享（服务层 DTO）：可见性与内容分离的结果。
 *
 * <p>{@code contentAuthorized=false} 是**降级态**（不抛错）：分享凭据本身有效（授予者有意签发，
 * 接收者有权"看见这份分享"），但接收者当前源权限无法覆盖分享时的范围——
 * {@code specJson}/{@code dataJson}/{@code asOf}/{@code completeness} 全部为空
 * （隐藏统计与快照不出库），{@code reasonCode}=scope-uncovered 说明原因。
 */
@Data
@Accessors(chain = true)
public class AiReportShareReadDTO {

    /** 分享编号 */
    private Long shareId;

    /** 报表编号 */
    private Long reportId;

    /** 报表名称 */
    private String reportName;

    /** 分享时固定的版本号 */
    private Integer versionNo;

    /** 模式（SNAPSHOT/REFRESHABLE） */
    private String mode;

    /** 内容是否出库（false = 降级态：以下内容字段全部为空） */
    private boolean contentAuthorized;

    /** 稳定原因码（完整成功为空；降级态为 scope-uncovered） */
    private String reasonCode;

    /** ReportSpec（降级态为空） */
    private String specJson;

    /** 快照数据（降级态为空） */
    private String dataJson;

    /** 数据截至时间（降级态为空） */
    private LocalDateTime asOf;

    /** 数据完整性（降级态为空） */
    private String completeness;

    /** 过期时间（空为长期有效） */
    private LocalDateTime expiresTime;
}
