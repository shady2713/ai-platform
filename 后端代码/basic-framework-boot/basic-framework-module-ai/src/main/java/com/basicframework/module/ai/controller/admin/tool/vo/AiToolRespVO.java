package com.basicframework.module.ai.controller.admin.tool.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 工具（协议层 VO）。 */
@Schema(description = "管理后台 - AI 工具")
@Data
@Accessors(chain = true)
public class AiToolRespVO {

    @Schema(description = "工具编号")
    private Long id;

    @Schema(description = "工具标识")
    private String code;

    @Schema(description = "工具名称")
    private String name;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "连接器编号")
    private Long connectorId;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;

    @Schema(description = "最新版本号（0 表示尚无版本）")
    private Integer latestVersionNo;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
