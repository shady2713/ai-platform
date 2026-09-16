package com.basicframework.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** 保证开发与测试日志不会写出 L4 SQL 参数。 */
class SensitiveMapperLoggingConfigurationTest {

    private static final List<String> SENSITIVE_MAPPER_LOGGERS = List.of(
            "com.basicframework.module.system.dal.mysql.session",
            "com.basicframework.module.system.dal.mysql.user.AdminUserMapper",
            "com.basicframework.module.system.dal.mysql.sms.SmsChannelMapper",
            "com.basicframework.module.system.dal.mysql.sms.SmsCodeMapper",
            "com.basicframework.module.system.dal.mysql.sms.SmsLogMapper");

    @Test
    void localAndTestProfilesKeepSensitiveMapperSqlParametersOutOfLogs() {
        for (String profile : List.of("application-local.yaml", "application-test.yaml")) {
            Properties properties = loadProperties(profile);
            for (String logger : SENSITIVE_MAPPER_LOGGERS) {
                assertThat(properties.getProperty("logging.level." + logger))
                        .as("%s must keep %s at INFO", profile, logger)
                        .isEqualTo("INFO");
            }
        }
    }

    private static Properties loadProperties(String profile) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(profile));
        return yaml.getObject();
    }
}
