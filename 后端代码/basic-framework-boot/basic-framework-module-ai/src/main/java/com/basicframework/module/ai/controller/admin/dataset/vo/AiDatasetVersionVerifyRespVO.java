package com.basicframework.module.ai.controller.admin.dataset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 版本验证/发布结论（协议层 VO）：只回列名与结论，不回上游数据。 */
@Schema(description = "管理后台 - AI 数据集版本验证/发布结论")
@Data
@Accessors(chain = true)
public class AiDatasetVersionVerifyRespVO {

    @Schema(description = "版本编号")
    private Long versionId;

    @Schema(description = "语义版本号")
    private Integer versionNo;

    @Schema(description = "版本状态（DRAFT/PUBLISHED）")
    private String status;

    @Schema(description = "验证状态（UNVERIFIED/VERIFIED/DRIFTED）")
    private String verificationStatus;

    @Schema(description = "定义引用了但上游缺失的列")
    private List<String> missingColumns;

    @Schema(description = "上游存在但类型不再兼容的列")
    private List<String> typeChangedColumns;

    @Schema(description = "上游新增（定义未使用）的列")
    private List<String> addedColumns;

    @Schema(description = "定义内容哈希")
    private String schemaHash;

    @Schema(description = "上游结构哈希（验证基线）")
    private String sourceSchemaHash;

    @Schema(description = "是否可发布")
    private Boolean publishable;
}
