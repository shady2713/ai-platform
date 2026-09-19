package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 资源绑定响应（协议层 VO）。 */
@Schema(description = "管理后台 - 服务资源绑定")
@Data
@Accessors(chain = true)
public class AiServiceResourceRespVO {

    @Schema(description = "绑定编号")
    private Long id;

    @Schema(description = "服务编号")
    private Long serviceId;

    @Schema(description = "发布版本编号（空表示草稿绑定）")
    private Long releaseId;

    @Schema(description = "资源类型")
    private String resourceType;

    @Schema(description = "资源标识")
    private String resourceKey;

    @Schema(description = "需要的动作")
    private List<String> actions;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
