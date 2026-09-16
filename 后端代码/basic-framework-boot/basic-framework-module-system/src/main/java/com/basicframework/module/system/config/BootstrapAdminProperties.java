package com.basicframework.module.system.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 部署者显式提供的独立初始化口令；为空时种子管理员保持禁用。 */
@Data
@Component
@ConfigurationProperties(prefix = "basic-framework.bootstrap-admin")
public class BootstrapAdminProperties {
    @ToString.Exclude
    private String password;
}
