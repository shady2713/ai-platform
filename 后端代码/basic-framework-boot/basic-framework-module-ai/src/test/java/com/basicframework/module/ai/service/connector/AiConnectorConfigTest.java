package com.basicframework.module.ai.service.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D01 声明式配置：白名单字段、恶意连接串参数拒绝、取值边界。 */
class AiConnectorConfigTest {

    private static void assertInvalid(Throwable throwable) {
        assertThat(throwable).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) throwable).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_CONNECTOR_CONFIG_INVALID.getCode());
    }

    @Test
    void acceptsDeclarativeHttpAndMysqlConfigs() {
        AiConnectorConfig http = AiConnectorConfig.parse(
                "HTTP",
                "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\",\"healthPath\":\"/health\","
                        + "\"authType\":\"BEARER\",\"timeoutMillis\":3000}");

        assertThat(http.type()).isEqualTo("HTTP");
        assertThat(http.string("baseUrl")).isEqualTo("https://crm.example.com");
        assertThat(http.integer("timeoutMillis")).isEqualTo(3000);
        assertThat(http.canonicalJson()).contains("\"healthPath\":\"/health\"");

        AiConnectorConfig mysql = AiConnectorConfig.parse(
                "MYSQL",
                "{\"host\":\"mysql.internal\",\"port\":3306,\"database\":\"crm\",\"username\":\"readonly\","
                        + "\"sslMode\":\"REQUIRED\"}");
        assertThat(mysql.jdbcUrl())
                .as("JDBC 地址只由已校验字段拼出，并强制关闭危险参数")
                .startsWith("jdbc:mysql://mysql.internal:3306/crm?")
                .contains("allowLoadLocalInfile=false")
                .contains("autoDeserialize=false");
    }

    @Test
    void rejectsConnectionStringParametersAndUnknownKeys() {
        // 整段连接串 / 未知参数（连接串注入面）一律拒绝
        for (String config : List.of(
                "{\"host\":\"mysql.internal\",\"port\":3306,\"database\":\"crm\",\"username\":\"ro\","
                        + "\"allowLoadLocalInfile\":\"true\"}",
                "{\"host\":\"mysql.internal\",\"port\":3306,\"database\":\"crm\",\"username\":\"ro\","
                        + "\"jdbcUrl\":\"jdbc:mysql://x/y\"}",
                "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\",\"headers\":{\"X-A\":\"1\"}}",
                "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\",\"extra\":1}")) {
            assertThatThrownBy(() -> AiConnectorConfig.parse(config.contains("baseUrl") ? "HTTP" : "MYSQL", config))
                    .as("未知键必须被拒绝：%s", config)
                    .satisfies(AiConnectorConfigTest::assertInvalid);
        }
    }

    @Test
    void rejectsMaliciousValuesInsteadOfEscaping() {
        // HTTP：非 https、带查询串/片段/用户信息、方法不在白名单
        for (String baseUrl : List.of(
                "http://crm.example.com",
                "https://crm.example.com?x=1",
                "https://crm.example.com#frag",
                "https://user:pass@crm.example.com",
                "https://crm.example.com/../admin")) {
            assertThatThrownBy(() ->
                            AiConnectorConfig.parse("HTTP", "{\"baseUrl\":\"" + baseUrl + "\",\"method\":\"GET\"}"))
                    .as("非法基址必须被拒绝：%s", baseUrl)
                    .satisfies(AiConnectorConfigTest::assertInvalid);
        }
        assertThatThrownBy(() -> AiConnectorConfig.parse(
                        "HTTP", "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"DELETE\"}"))
                .satisfies(AiConnectorConfigTest::assertInvalid);
        assertThatThrownBy(() -> AiConnectorConfig.parse(
                        "HTTP",
                        "{\"baseUrl\":\"https://crm.example.com\",\"method\":\"GET\",\"healthPath\":\"/a?b=1\"}"))
                .satisfies(AiConnectorConfigTest::assertInvalid);

        // MYSQL：主机名带协议/斜杠/引号、库名与用户名含连接串语法、端口越界
        for (String host : List.of(
                "jdbc:mysql://x", "mysql.internal:3306", "mysql.internal/x", "mysql.internal' or '1'='1", "a b")) {
            assertThatThrownBy(() -> AiConnectorConfig.parse(
                            "MYSQL",
                            "{\"host\":\"" + host + "\",\"port\":3306,\"database\":\"crm\",\"username\":\"ro\"}"))
                    .as("非法主机必须被拒绝：%s", host)
                    .satisfies(AiConnectorConfigTest::assertInvalid);
        }
        for (String database : List.of("crm;drop", "crm?x=1", "crm`x", "cr m")) {
            assertThatThrownBy(() -> AiConnectorConfig.parse(
                            "MYSQL",
                            "{\"host\":\"mysql.internal\",\"port\":3306,\"database\":\"" + database
                                    + "\",\"username\":\"ro\"}"))
                    .as("非法库名必须被拒绝：%s", database)
                    .satisfies(AiConnectorConfigTest::assertInvalid);
        }
        assertThatThrownBy(() -> AiConnectorConfig.parse(
                        "MYSQL",
                        "{\"host\":\"mysql.internal\",\"port\":70000,\"database\":\"crm\",\"username\":\"ro\"}"))
                .satisfies(AiConnectorConfigTest::assertInvalid);
        assertThatThrownBy(() -> AiConnectorConfig.parse(
                        "MYSQL",
                        "{\"host\":\"mysql.internal\",\"port\":3306,\"database\":\"crm\",\"username\":\"ro\","
                                + "\"sslMode\":\"NONE\"}"))
                .satisfies(AiConnectorConfigTest::assertInvalid);
    }

    @Test
    void rejectsUnknownTypeMissingKeysAndNonObjectConfig() {
        assertThatThrownBy(() -> AiConnectorConfig.parse("ORACLE", "{\"host\":\"x\"}"))
                .satisfies(AiConnectorConfigTest::assertInvalid);
        assertThatThrownBy(() -> AiConnectorConfig.parse("HTTP", "{\"method\":\"GET\"}"))
                .as("缺少必填键（baseUrl）必须被拒绝")
                .satisfies(AiConnectorConfigTest::assertInvalid);
        assertThatThrownBy(() -> AiConnectorConfig.parse("HTTP", "not-json"))
                .satisfies(AiConnectorConfigTest::assertInvalid);
        assertThatThrownBy(() -> AiConnectorConfig.parse("HTTP", "[1,2]"))
                .satisfies(AiConnectorConfigTest::assertInvalid);
        assertThatThrownBy(() -> AiConnectorConfig.parse(null, "{}")).satisfies(AiConnectorConfigTest::assertInvalid);

        assertThat(AiConnectorConfig.supportedTypes()).containsExactly("HTTP", "MYSQL");
    }
}
