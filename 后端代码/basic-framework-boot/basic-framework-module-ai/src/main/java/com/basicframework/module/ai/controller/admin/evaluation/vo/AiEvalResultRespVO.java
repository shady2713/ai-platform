package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 评测结果（Q04，协议层 VO）：逐条核对结论只有稳定值（规则/期望/实际/说明），
 * 不含提示词与响应正文；{@code caseDigest} 与 {@code resultDigest} 保证可复现。
 */
@Schema(description = "管理后台 - AI 评测结果")
@Data
@Accessors(chain = true)
public class AiEvalResultRespVO {

    @Schema(description = "结果编号")
    private Long id;

    @Schema(description = "评测运行编号")
    private Long runId;

    @Schema(description = "样例编号")
    private Long caseId;

    @Schema(description = "样例标识（快照）")
    private String caseKey;

    @Schema(description = "严重级别（快照）")
    private String severity;

    @Schema(description = "判定（PASSED/FAILED/ERROR/REVIEW_REQUIRED）")
    private String status;

    @Schema(description = "期望版本标识（快照）")
    private String expectVersion;

    @Schema(description = "实际版本标识")
    private String observedVersion;

    @Schema(description = "本次评测用例产生的运行编号")
    private Long runRef;

    @Schema(description = "逐条核对结论（规则/期望/实际/说明）")
    private String verdictJson;

    @Schema(description = "错误码（未能执行时给出）")
    private String failureCode;

    @Schema(description = "冻结的样例摘要")
    private String caseDigest;

    @Schema(description = "结果摘要")
    private String resultDigest;

    @Schema(description = "人工复核状态（NOT_REQUIRED/PENDING/APPROVED/REJECTED）")
    private String reviewStatus;

    @Schema(description = "复核备注")
    private String reviewNote;

    @Schema(description = "复核人")
    private String reviewedBy;

    @Schema(description = "复核时间")
    private LocalDateTime reviewedTime;
}
