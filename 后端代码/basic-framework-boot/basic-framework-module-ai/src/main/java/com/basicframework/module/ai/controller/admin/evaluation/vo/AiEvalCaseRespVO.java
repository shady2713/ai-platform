package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 评测样例（Q04，协议层 VO）：合成问题 + 期望规则 + 级别。 */
@Schema(description = "管理后台 - AI 评测样例")
@Data
@Accessors(chain = true)
public class AiEvalCaseRespVO {

    @Schema(description = "样例编号")
    private Long id;

    @Schema(description = "所属套件编号")
    private Long suiteId;

    @Schema(description = "样例标识")
    private String caseKey;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "严重级别（BLOCKER/MAJOR/MINOR）")
    private String severity;

    @Schema(description = "合成问题")
    private String question;

    @Schema(description = "期望的模型/服务版本标识")
    private String expectVersion;

    @Schema(description = "期望规则（规范化 JSON）")
    private String checksJson;

    @Schema(description = "是否需要人工复核")
    private Boolean needsReview;

    @Schema(description = "乐观锁版本")
    private Integer version;
}
