package com.basicframework.framework.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Base64;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/** 安全框架的配置属性类 */
@ConfigurationProperties(prefix = "basic-framework.security")
@Validated
@Data
public class SecurityProperties {

    /**
     * HTTP 请求时，访问令牌的请求 Header
     */
    @NotEmpty(message = "Token Header 不能为空")
    private String tokenHeader = "Authorization";

    /**
     * PasswordEncoder 加密复杂度，越高开销越大
     */
    @NotNull(message = "PasswordEncoder 强度不能为空")
    @Min(value = 4, message = "PasswordEncoder 强度不能小于 4")
    @Max(value = 31, message = "PasswordEncoder 强度不能大于 31")
    private Integer passwordEncoderLength = 10;

    /** Base64 编码的 32 字节凭据主密钥；生产环境必须由 Secret 管理注入。 */
    @ToString.Exclude
    private String credentialEncryptionKey;

    @Valid
    @NotNull
    private RefreshCookie refreshCookie = new RefreshCookie();

    @AssertTrue(message = "credential-encryption-key 必须为空或 32 字节 Base64 值")
    public boolean isCredentialEncryptionKeyValid() {
        if (!StringUtils.hasText(credentialEncryptionKey)) {
            return true;
        }
        try {
            return Base64.getDecoder().decode(credentialEncryptionKey).length == 32;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Data
    public static class RefreshCookie {

        /** 生产环境必须启用，确保刷新令牌只经 HTTPS 发送。 */
        private boolean secure;
    }
}
