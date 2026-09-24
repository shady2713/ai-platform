package com.basicframework.module.ai.controller.admin.theme.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 主题修订（协议层 VO）：返回已规范化 token 与布局，便于管理端直接预览。 */
@Schema(description = "管理后台 - AI 主题修订")
@Data
@Accessors(chain = true)
public class AiThemeRespVO {

    @Schema(description = "主题修订编号")
    private Long id;

    @Schema(description = "主题对外标识（thm_ 前缀）")
    private String publicId;

    @Schema(description = "所属应用编号")
    private Long applicationId;

    @Schema(description = "修订号（应用内递增）")
    private Integer revision;

    @Schema(description = "ThemeTokens v1（规范化 JSON）")
    @ToString.Exclude
    private String tokensJson;

    @Schema(description = "布局与排版选项（规范化 JSON）")
    private String layoutJson;

    @Schema(description = "内容摘要（SHA-256；缓存键与审计比对）")
    @ToString.Exclude
    private String tokensFingerprint;

    @Schema(description = "发布状态（DRAFT/PUBLISHED/SUPERSEDED）")
    private String publicationState;

    @Schema(description = "发布时间（首次发布时写入）")
    private LocalDateTime publishedTime;

    @Schema(description = "乐观锁版本")
    private Integer version;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
