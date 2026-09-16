package com.basicframework.module.system.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 用户自选密码的部署相关保留词；结构边界由字段契约固定，不开放配置。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "basic-framework.security.password-policy")
public class PasswordPolicyProperties {

    @NotEmpty
    private Set<@NotBlank String> reservedTerms = Set.of("basicframework");
}
