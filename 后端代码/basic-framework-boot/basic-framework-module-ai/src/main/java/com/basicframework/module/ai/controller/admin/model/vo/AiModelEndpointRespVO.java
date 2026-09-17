package com.basicframework.module.ai.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.ToString;

/**
 * 模型端点响应。
 *
 * <p>不包含凭据密文与任何可还原凭据的字段：只暴露 {@code credentialConfigured} 标识是否已配置。
 */
@Data
public class AiModelEndpointRespVO {

    @Schema(description = "端点编号")
    private Long id;

    @Schema(description = "端点名称")
    private String name;

    @Schema(description = "提供方标识")
    private String provider;

    @Schema(description = "基础地址")
    private String baseUrl;

    @Schema(description = "模型标识（当前版本）")
    private String modelId;

    @Schema(description = "能力集合")
    private List<String> capabilities;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "是否已被发布服务引用")
    private Boolean referenced;

    @Schema(description = "当前非秘密配置版本")
    private Integer configRevision;

    @Schema(description = "凭据版本（0 表示未配置）")
    @ToString.Exclude
    private Integer credentialRevision;

    @Schema(description = "是否已配置凭据（不返回凭据本身）")
    @ToString.Exclude
    private Boolean credentialConfigured;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
