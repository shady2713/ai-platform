package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表刷新响应（应用端协议层 VO）：OK/UNCHANGED/FAILED 都是正常结果。
 *
 * <p>失败时给出稳定原因码与尝试时间，界面显示"上次刷新时间 + 失败原因"，旧结果保持可读（AT-047）。
 */
@Schema(description = "AI 应用端 - 报表刷新响应")
@Data
@Accessors(chain = true)
public class AiReportRefreshRespVO {

    @Schema(description = "结果：OK（已刷新并切换生效版本）/ UNCHANGED（数据未变化）/ FAILED（保留旧结果）")
    private String status;

    @Schema(description = "报表编号")
    private Long reportId;

    @Schema(description = "本次刷新基于的版本号")
    private Integer baseVersionNo;

    @Schema(description = "本次刷新产生的版本号（OK 时存在）")
    private Integer resultVersionNo;

    @Schema(description = "尝试时间（成功时即本次数据的截至时间）")
    private LocalDateTime asOf;

    @Schema(description = "数据完整性：COMPLETE / PARTIAL")
    private String completeness;

    @Schema(description = "稳定原因码（失败为平台错误码；未变化为 UNCHANGED）")
    private String reason;

    @Schema(description = "绑定数据（OK/UNCHANGED 时存在）")
    private String dataJson;

    @Schema(description = "说明")
    private String note;
}
