package com.basicframework.module.infra.api.file.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建受控私有文件的请求。
 *
 * <p>文件必须声明业务绑定（businessType + businessId）：读取授权由该业务类型的
 * {@link FileBusinessAccessProvider} 决定，管理权限不得用于绕过业务授权。
 */
@Data
public class FileCreateReqDTO {

    /** 文件内容，不能为空 */
    @NotEmpty(message = "文件内容不能为空")
    private byte[] content;

    /** 文件名，允许为空 */
    private String name;

    /** 文件 MIME 类型，允许为空 */
    private String type;

    /** 业务类型，例如 ai_knowledge_document；必须与已注册的业务授权 Provider 一致 */
    @NotBlank(message = "业务类型不能为空")
    private String businessType;

    /** 业务对象编号 */
    @NotNull(message = "业务对象编号不能为空")
    private Long businessId;

    /** 操作主体，必须来自已认证会话 */
    @Valid
    @NotNull(message = "操作主体不能为空")
    private FileSubjectDTO subject;
}
