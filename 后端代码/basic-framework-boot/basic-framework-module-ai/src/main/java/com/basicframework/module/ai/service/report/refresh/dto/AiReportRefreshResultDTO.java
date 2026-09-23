package com.basicframework.module.ai.service.report.refresh.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表刷新结果（服务层 DTO）：三种结果都是**正常结果**。
 *
 * <p>OK 产生新版本并切换生效版本；UNCHANGED 表示上游数据没变（重复刷新幂等，不产生新版本）；
 * FAILED 保留旧结果并带稳定原因与尝试时间（AT-047）。失败不是异常：界面要显示"上次刷新失败 + 原因"。
 */
@Data
@Accessors(chain = true)
public class AiReportRefreshResultDTO {

    /** 结果：已刷新（产生新版本）。 */
    public static final String STATUS_OK = "OK";

    /** 结果：数据未变化（不产生新版本）。 */
    public static final String STATUS_UNCHANGED = "UNCHANGED";

    /** 结果：失败（保留旧结果）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 结果（OK/UNCHANGED/FAILED） */
    private String status;

    /** 报表编号 */
    private Long reportId;

    /** 本次刷新基于的版本号 */
    private Integer baseVersionNo;

    /** 本次刷新产生的版本号（OK 时存在） */
    private Integer resultVersionNo;

    /** 尝试时间（成功时即本次数据的截至时间） */
    private LocalDateTime asOf;

    /** 数据完整性（COMPLETE/PARTIAL） */
    private String completeness;

    /** 稳定原因码（失败为平台错误码；未变化为 UNCHANGED） */
    private String reason;

    /** 绑定数据（OK/UNCHANGED 时存在；与版本数据同形） */
    private String dataJson;

    /** 说明 */
    private String note;
}
