package com.basicframework.module.ai.controller.app.v1.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 业务文件上传响应（A07）。 */
@Schema(description = "应用端 - 业务文件上传响应")
@Data
@Accessors(chain = true)
public class AiFileUploadRespVO {

    @Schema(description = "文件编号")
    private Long fileId;

    @Schema(description = "业务类型")
    private String businessType;

    @Schema(description = "业务对象标识")
    private String businessKey;

    @Schema(description = "文件名")
    private String name;

    @Schema(description = "大小（字节）")
    private Long size;
}
