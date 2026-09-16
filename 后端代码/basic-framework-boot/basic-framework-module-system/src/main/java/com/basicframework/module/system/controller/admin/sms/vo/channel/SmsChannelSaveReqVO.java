package com.basicframework.module.system.controller.admin.sms.vo.channel;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.framework.common.validation.InEnum;
import com.basicframework.module.system.framework.sms.core.enums.SmsChannelEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import org.hibernate.validator.constraints.URL;
import org.springframework.util.StringUtils;

@Schema(description = "管理后台 - 短信渠道创建/修改 Request VO")
@Data
public class SmsChannelSaveReqVO {

    @Schema(description = "编号", example = "1024")
    @Positive(message = "编号必须大于 0")
    private Long id;

    @Schema(description = "短信签名", requiredMode = Schema.RequiredMode.REQUIRED, example = "基础框架")
    @NotBlank(message = "短信签名不能为空")
    @Size(max = 12, message = "短信签名长度不能超过 12 个字符")
    private String signature;

    @Schema(
            description = "渠道编码，参见 SmsChannelEnum 枚举类",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "YUN_PIAN")
    @NotBlank(message = "渠道编码不能为空")
    @Size(max = 63, message = "渠道编码长度不能超过 63 个字符")
    @InEnum(value = SmsChannelEnum.class, message = "渠道编码必须是 {value}")
    private String code;

    @Schema(description = "启用状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "启用状态不能为空")
    @InEnum(value = CommonStatusEnum.class, message = "启用状态必须是 {value}")
    private Integer status;

    @Schema(description = "备注", example = "好吃！")
    @Size(max = 255, message = "备注长度不能超过 255 个字符")
    private String remark;

    @Schema(description = "短信 API 的账号；修改时留空表示保留原账号", example = "sms-access-key")
    @Size(max = 128, message = "短信 API 账号长度不能超过 128 个字符")
    @ToString.Exclude
    private String apiKey;

    @Schema(description = "短信 API 的密钥；修改时留空表示保留原密钥", example = "deployment-secret")
    @Size(max = 256, message = "短信 API 密钥长度不能超过 256 个字符")
    @ToString.Exclude
    private String apiSecret;

    @Schema(description = "短信发送回调 URL", example = "http://www.example.com")
    @URL(message = "回调 URL 格式不正确")
    @Pattern(regexp = "https?://.*", message = "回调 URL 只允许使用 HTTP 或 HTTPS")
    @Size(max = 255, message = "回调 URL 长度不能超过 255 个字符")
    private String callbackUrl;

    @AssertTrue(message = "创建短信渠道时 API Key 和 API Secret 不能为空")
    public boolean isCredentialsValid() {
        return id != null || StringUtils.hasText(apiKey) && StringUtils.hasText(apiSecret);
    }
}
