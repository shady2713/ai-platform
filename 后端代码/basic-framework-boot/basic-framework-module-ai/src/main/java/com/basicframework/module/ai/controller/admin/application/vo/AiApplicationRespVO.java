package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 应用响应（协议层 VO）：**永不包含凭据摘要与明文**，只有"是否已配置可用凭据"。 */
@Schema(description = "管理后台 - AI 应用")
@Data
@Accessors(chain = true)
public class AiApplicationRespVO {

    @Schema(description = "应用编号")
    private Long id;

    @Schema(description = "应用标识")
    private String appCode;

    @Schema(description = "应用名称")
    private String name;

    @Schema(description = "应用说明")
    private String description;

    @Schema(description = "精确 Origin 列表")
    private List<String> origins;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "是否存在可用的客户端凭据（不返回摘要或明文）")
    @ToString.Exclude
    private Boolean credentialConfigured;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
