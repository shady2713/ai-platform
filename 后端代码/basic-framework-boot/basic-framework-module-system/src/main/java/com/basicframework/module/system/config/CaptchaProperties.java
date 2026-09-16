package com.basicframework.module.system.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 登录图形验证码开关的部署配置。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "basic-framework.captcha")
public class CaptchaProperties {

    /** 默认启用，非生产环境必须在其 profile 中显式关闭。 */
    private boolean enable = true;
}
