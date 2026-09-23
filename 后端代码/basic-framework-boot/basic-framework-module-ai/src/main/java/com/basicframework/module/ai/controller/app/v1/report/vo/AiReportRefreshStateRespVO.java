package com.basicframework.module.ai.controller.app.v1.report.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 报表刷新状态响应（应用端协议层 VO）：上次刷新时间/结果/原因 + 上一次结果数据。
 *
 * <p>数据在服务层按当前 ACL 复核后才会返回（无法证明覆盖即 409），协议层不做授权判断。
 */
@Schema(description = "AI 应用端 - 报表刷新状态响应")
@Data
@Accessors(chain = true)
public class AiReportRefreshStateRespVO {

    @Schema(description = "报表编号")
    private Long reportId;

    @Schema(description = "是否已有刷新尝试（false 表示从未刷新）")
    private boolean attempted;

    @Schema(description = "最近一次尝试的结果（OK/UNCHANGED/FAILED）")
    private String status;

    @Schema(description = "最近一次尝试时间")
    private LocalDateTime asOf;

    @Schema(description = "最近一次尝试的稳定原因码")
    private String reason;

    @Schema(description = "最近一次成功刷新的数据完整性")
    private String completeness;

    @Schema(description = "最近一次成功刷新产生的版本号")
    private Integer resultVersionNo;

    @Schema(description = "最近一次成功刷新的绑定数据（已按当前 ACL 复核）")
    private String dataJson;
}
