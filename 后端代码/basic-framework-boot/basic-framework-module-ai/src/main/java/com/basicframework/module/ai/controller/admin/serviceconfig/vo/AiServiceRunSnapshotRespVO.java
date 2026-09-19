package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 运行解析结果（协议层 VO）：新运行或固定会话将要使用的发布版本固定值。
 *
 * <p>只暴露版本与资源标识，不包含提示词正文、授权结论或任何凭据：控制台据此展示
 * "后续运行使用哪个版本、固定了哪些资源版本"。
 */
@Schema(description = "管理后台 - AI 服务运行解析结果")
@Data
@Accessors(chain = true)
public class AiServiceRunSnapshotRespVO {

    @Schema(description = "服务编号")
    private Long serviceId;

    @Schema(description = "解析到的发布版本编号")
    private Long releaseId;

    @Schema(description = "发布版本号")
    private Integer releaseVersion;

    @Schema(description = "版本状态（ACTIVE/RETIRED）")
    private String status;

    @Schema(description = "固定的内容摘要（发布内容的稳定标识）")
    private String contentHash;

    @Schema(description = "固定的模型端点编号")
    private Long modelEndpointId;

    @Schema(description = "固定的端点配置版本（模型修订号）")
    private Integer modelRevision;

    @Schema(description = "是否来自会话固定版本（false 表示按别名解析的新运行）")
    private boolean pinned;

    @Schema(description = "固定的资源版本清单")
    private List<AiServiceRunResourceRespVO> resources;
}
