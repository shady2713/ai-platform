package com.basicframework.module.infra.api.file.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除受控文件的请求。删除按引用语义执行：仅在主体对该业务对象具备删除权限时受理。 */
@Data
public class FileDeleteReqDTO {

    /** 文件编号 */
    @NotNull(message = "文件编号不能为空")
    private Long fileId;

    /** 请求主体，必须来自已认证会话 */
    @Valid
    @NotNull(message = "请求主体不能为空")
    private FileSubjectDTO subject;
}
