package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 应用新增/修改请求（协议层 VO）。 */
@Schema(description = "管理后台 - AI 应用新增/修改")
@Data
@Accessors(chain = true)
public class AiApplicationSaveReqVO {

    @Schema(description = "应用编号（修改时必填）")
    private Long id;

    @Schema(description = "应用标识（小写字母开头，3-64 位；创建后不可修改）", example = "crm-portal")
    @Pattern(regexp = "^[a-z][a-z0-9_-]{2,63}$", message = "应用标识只能是小写字母开头的字母、数字、下划线或连字符，长度 3-64")
    private String appCode;

    @Schema(description = "应用名称", example = "CRM 门户")
    @NotNull
    @Size(min = 1, max = 128, message = "应用名称长度必须在 1-128 之间")
    private String name;

    @Schema(description = "应用说明")
    @Size(max = 512, message = "应用说明不能超过 512 个字符")
    private String description;

    @Schema(description = "精确 Origin 列表（无路径、无通配）", example = "[\"https://crm.example.com\"]")
    @NotEmpty(message = "至少配置一个精确 Origin")
    private List<String> origins;

    @Schema(description = "乐观锁版本（修改时必填）")
    private Integer version;
}
