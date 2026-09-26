package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 评测套件（Q04，协议层 VO）：只有配置与摘要，不含任何凭据。 */
@Schema(description = "管理后台 - AI 评测套件")
@Data
@Accessors(chain = true)
public class AiEvalSuiteRespVO {

    @Schema(description = "套件编号")
    private Long id;

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "套件标识")
    private String code;

    @Schema(description = "套件名称")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "被评测的服务编号")
    private Long serviceId;

    @Schema(description = "执行主体类型")
    private String subjectType;

    @Schema(description = "执行主体标识（合成主体）")
    private String externalUserId;

    @Schema(description = "样例数据分级")
    private String dataLevel;

    @Schema(description = "状态（DRAFT/FROZEN）")
    private String status;

    @Schema(description = "修订号")
    private Integer revision;

    @Schema(description = "样例数（冻结时的事实值）")
    private Integer caseCount;

    @Schema(description = "最近冻结时间")
    private LocalDateTime frozenTime;

    @Schema(description = "冻结内容摘要（套件修改后仍在历史运行里保留原值）")
    private String contentDigest;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
