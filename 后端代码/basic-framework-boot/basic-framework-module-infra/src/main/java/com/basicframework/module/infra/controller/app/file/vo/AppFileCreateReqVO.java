package com.basicframework.module.infra.controller.app.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

@Schema(description = "应用端 - 文件创建 Request VO")
@Data
@ToString(exclude = {"uploadToken"})
public class AppFileCreateReqVO {

    @Schema(description = "一次性上传完成凭据", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "上传凭据不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "上传凭据格式不正确")
    private String uploadToken;
}
