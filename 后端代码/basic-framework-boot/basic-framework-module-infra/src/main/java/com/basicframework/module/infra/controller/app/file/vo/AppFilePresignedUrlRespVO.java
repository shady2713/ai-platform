package com.basicframework.module.infra.controller.app.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Schema(description = "应用端 - 文件预签名地址 Response VO")
@Data
@ToString(exclude = {"uploadToken"})
@NoArgsConstructor
@AllArgsConstructor
public class AppFilePresignedUrlRespVO {

    @Schema(description = "文件上传 URL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String uploadUrl;

    @Schema(description = "一次性上传完成凭据", requiredMode = Schema.RequiredMode.REQUIRED)
    private String uploadToken;
}
