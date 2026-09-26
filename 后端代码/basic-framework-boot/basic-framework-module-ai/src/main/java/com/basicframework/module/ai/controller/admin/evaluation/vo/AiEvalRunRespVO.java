package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 评测运行（Q04，协议层 VO）。
 *
 * <p>计数口径：{@code passedCount} 只计 PASSED；FAILED 与 REVIEW_REQUIRED 计入
 * {@code failedCount}（后者待复核）；ERROR 计入 {@code errorCount}；三者之和＝{@code caseTotal}。
 * {@code suiteDigest} 是执行时的套件摘要：套件之后怎么改都不会改变它。
 */
@Schema(description = "管理后台 - AI 评测运行")
@Data
@Accessors(chain = true)
public class AiEvalRunRespVO {

    @Schema(description = "评测运行编号")
    private Long id;

    @Schema(description = "套件编号")
    private Long suiteId;

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "服务编号")
    private Long serviceId;

    @Schema(description = "执行时的套件修订号")
    private Integer suiteRevision;

    @Schema(description = "执行时的套件内容摘要")
    private String suiteDigest;

    @Schema(description = "状态（RUNNING/COMPLETED/FAILED）")
    private String status;

    @Schema(description = "样例总数")
    private Integer caseTotal;

    @Schema(description = "通过数")
    private Integer passedCount;

    @Schema(description = "失败数（含待复核）")
    private Integer failedCount;

    @Schema(description = "错误数（未能执行）")
    private Integer errorCount;

    @Schema(description = "逐例快照摘要（caseKey + 摘要 + 级别 + 是否需复核）")
    private String summaryJson;

    @Schema(description = "开始时间")
    private LocalDateTime startedTime;

    @Schema(description = "结束时间")
    private LocalDateTime finishedTime;
}
