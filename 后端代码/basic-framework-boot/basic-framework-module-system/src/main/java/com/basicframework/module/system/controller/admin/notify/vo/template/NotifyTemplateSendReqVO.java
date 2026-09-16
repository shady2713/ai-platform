package com.basicframework.module.system.controller.admin.notify.vo.template;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.validation.InEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;

@Schema(description = "管理后台 - 站内信模板的发送 Request VO")
@Data
public class NotifyTemplateSendReqVO {

    @Schema(description = "用户id", requiredMode = Schema.RequiredMode.REQUIRED, example = "01")
    @NotNull(message = "用户id不能为空")
    @Positive(message = "用户id必须大于 0")
    private Long userId;

    @Schema(description = "用户类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "用户类型不能为空")
    @Positive(message = "用户类型只允许会员或管理员")
    @InEnum(value = UserTypeEnum.class, message = "用户类型必须是 {value}")
    private Integer userType;

    @Schema(description = "模板编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "01")
    @NotBlank(message = "模板编码不能为空")
    @Size(max = 64, message = "模板编码长度不能超过 64 个字符")
    private String templateCode;

    @Schema(description = "模板参数")
    @Size(max = 50, message = "模板参数不能超过 50 项")
    private Map<@NotBlank @Size(max = 64) String, @NotNull Object> templateParams;
}
