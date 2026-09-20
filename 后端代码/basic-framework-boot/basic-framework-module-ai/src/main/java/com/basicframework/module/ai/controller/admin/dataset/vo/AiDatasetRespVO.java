package com.basicframework.module.ai.controller.admin.dataset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 数据集（协议层 VO）：只描述来源与版本号，不含上游数据。 */
@Schema(description = "管理后台 - AI 数据集")
@Data
@Accessors(chain = true)
public class AiDatasetRespVO {

    @Schema(description = "数据集编号")
    private Long id;

    @Schema(description = "数据集标识")
    private String code;

    @Schema(description = "数据集名称")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "连接器编号")
    private Long connectorId;

    @Schema(description = "来源对象（schema.table）")
    private String sourceObject;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;

    @Schema(description = "最新语义版本号（0 表示尚无版本）")
    private Integer latestVersionNo;

    @Schema(description = "最近发布的语义版本号（0 表示未发布）")
    private Integer publishedVersionNo;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
