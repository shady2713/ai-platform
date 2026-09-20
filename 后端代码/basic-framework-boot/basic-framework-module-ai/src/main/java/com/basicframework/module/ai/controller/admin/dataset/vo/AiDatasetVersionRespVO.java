package com.basicframework.module.ai.controller.admin.dataset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 语义版本（协议层 VO）：定义快照 + 验证/漂移结论。 */
@Schema(description = "管理后台 - AI 数据集语义版本")
@Data
@Accessors(chain = true)
public class AiDatasetVersionRespVO {

    @Schema(description = "版本编号")
    private Long id;

    @Schema(description = "数据集编号")
    private Long datasetId;

    @Schema(description = "语义版本号")
    private Integer versionNo;

    @Schema(description = "状态（DRAFT/PUBLISHED）")
    private String status;

    @Schema(description = "语义定义（规范化 JSON）")
    private String definitionJson;

    @Schema(description = "定义内容哈希")
    private String schemaHash;

    @Schema(description = "上游结构哈希（验证基线）")
    private String sourceSchemaHash;

    @Schema(description = "验证状态（UNVERIFIED/VERIFIED/DRIFTED）")
    private String verificationStatus;

    @Schema(description = "最近一次漂移结论")
    private String driftJson;

    @Schema(description = "最近一次验证时间")
    private LocalDateTime verifiedAt;

    @Schema(description = "发布时间")
    private LocalDateTime publishedAt;

    @Schema(description = "乐观锁版本")
    private Integer version;
}
