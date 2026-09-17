package com.basicframework.module.infra.api.file.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 读取受控文件（元数据或内容）的请求。 */
@Data
public class FileReadReqDTO {

    /** 文件编号 */
    @NotNull(message = "文件编号不能为空")
    private Long fileId;

    /** 请求主体，必须来自已认证会话 */
    @Valid
    @NotNull(message = "请求主体不能为空")
    private FileSubjectDTO subject;
}
