package com.basicframework.module.system.controller.admin.sms.vo.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;
import org.hibernate.validator.constraints.URL;

@Schema(description = "管理后台 - 短信渠道 Response VO")
@Data
public class SmsChannelRespVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    private Long id;

    @Schema(description = "短信签名", requiredMode = Schema.RequiredMode.REQUIRED, example = "基础框架")
    @NotNull(message = "短信签名不能为空")
    private String signature;

    @Schema(
            description = "渠道编码，参见 SmsChannelEnum 枚举类",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "YUN_PIAN")
    private String code;

    @Schema(description = "启用状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "启用状态不能为空")
    private Integer status;

    @Schema(description = "备注", example = "好吃！")
    private String remark;

    @Schema(description = "是否已配置短信 API 账号", requiredMode = Schema.RequiredMode.REQUIRED)
    @ToString.Exclude
    private Boolean apiKeyConfigured;

    @Schema(description = "是否已配置短信 API 密钥", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean apiSecretConfigured;

    @Schema(description = "短信发送回调 URL", example = "https://www.example.com")
    @URL(message = "回调 URL 格式不正确")
    private String callbackUrl;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;
}
