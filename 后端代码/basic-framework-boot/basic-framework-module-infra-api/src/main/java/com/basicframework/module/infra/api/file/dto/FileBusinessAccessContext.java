package com.basicframework.module.infra.api.file.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 业务文件读取授权的判定上下文：infra 把"哪个主体、读哪个业务对象的哪个文件"交给业务 Provider 决策。
 */
@Data
public class FileBusinessAccessContext {

    /** 文件编号 */
    @NotNull(message = "文件编号不能为空")
    private Long fileId;

    /** 业务类型 */
    @NotBlank(message = "业务类型不能为空")
    private String businessType;

    /** 业务对象编号 */
    @NotNull(message = "业务对象编号不能为空")
    private Long businessId;

    /** 请求主体，必须来自已认证会话 */
    @NotNull(message = "请求主体不能为空")
    private FileSubjectDTO subject;
}
