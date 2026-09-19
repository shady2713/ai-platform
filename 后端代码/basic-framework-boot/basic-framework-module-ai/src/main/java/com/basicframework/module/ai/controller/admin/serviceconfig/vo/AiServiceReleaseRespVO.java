package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** 发布版本（协议层 VO）：内容列不可变，接口只读。 */
@Schema(description = "管理后台 - AI 服务发布版本")
@Data
@Accessors(chain = true)
public class AiServiceReleaseRespVO {

    @Schema(description = "发布版本编号")
    private Long id;

    @Schema(description = "服务编号")
    private Long serviceId;

    @Schema(description = "发布版本号")
    private Integer releaseVersion;

    @Schema(description = "模型端点编号")
    private Long modelEndpointId;

    @Schema(description = "发布时固定的端点配置版本")
    private Integer endpointConfigRevision;

    @Schema(description = "发布时固定的能力集合")
    private String requiredCapabilities;

    @Schema(description = "发布时冻结的评测门槛")
    private Integer evalThreshold;

    @Schema(description = "发布内容摘要（评测与回退的稳定标识）")
    private String contentHash;

    @Schema(description = "状态（CANDIDATE/ACTIVE/RETIRED）")
    private String status;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
