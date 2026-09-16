package com.basicframework.module.system.framework.sms.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "basic-framework.sms-callback")
@Validated
@Data
public class SmsCallbackProperties {

    static final int TOKEN_HEX_LENGTH = 64;

    private boolean enabled;

    @ToString.Exclude
    private String token;

    @Min(1_024)
    @Max(4_194_304)
    private int maxPayloadLength = 1_048_576;

    @AssertTrue(message = "启用短信回调时 token 必须为 32 字节随机值的 64 位十六进制编码")
    public boolean isTokenConfigurationValid() {
        return !enabled || token != null && token.matches("[0-9a-fA-F]{" + TOKEN_HEX_LENGTH + "}");
    }
}
