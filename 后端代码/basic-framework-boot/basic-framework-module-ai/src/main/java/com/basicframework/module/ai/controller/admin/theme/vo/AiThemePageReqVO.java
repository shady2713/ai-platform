package com.basicframework.module.ai.controller.admin.theme.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 主题修订分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 主题修订分页")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiThemePageReqVO extends PageParam {

    @Schema(description = "应用编号", example = "1")
    @Positive(message = "应用编号必须为正数")
    private Long applicationId;

    @Schema(description = "发布状态（DRAFT/PUBLISHED/SUPERSEDED）", example = "PUBLISHED")
    private String publicationState;
}
