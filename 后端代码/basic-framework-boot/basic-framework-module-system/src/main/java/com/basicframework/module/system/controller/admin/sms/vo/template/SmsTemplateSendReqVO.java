package com.basicframework.module.system.controller.admin.sms.vo.template;

import com.basicframework.framework.common.validation.Mobile;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Data;
import lombok.ToString;

@Schema(description = "管理后台 - 短信模板发送 Request VO")
@Data
public class SmsTemplateSendReqVO {

    @Schema(description = "手机号", requiredMode = Schema.RequiredMode.REQUIRED, example = "13812345678")
    @NotBlank(message = "手机号不能为空")
    @Mobile
    @ToString.Exclude
    private String mobile;

    @Schema(description = "模板编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "test_01")
    @NotBlank(message = "模板编码不能为空")
    private String templateCode;

    @Schema(description = "模板参数")
    private Map<String, Object> templateParams;
}
