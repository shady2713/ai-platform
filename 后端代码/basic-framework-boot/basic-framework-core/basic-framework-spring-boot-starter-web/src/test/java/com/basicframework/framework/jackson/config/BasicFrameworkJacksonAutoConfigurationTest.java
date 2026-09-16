package com.basicframework.framework.jackson.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class BasicFrameworkJacksonAutoConfigurationTest {

    private final BasicFrameworkJacksonAutoConfiguration configuration = new BasicFrameworkJacksonAutoConfiguration();

    @Test
    void builderCustomizer_appliesSafeLongAndEpochTimeContracts() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        configuration.ldtEpochMillisCustomizer().customize(builder);
        ObjectMapper objectMapper = builder.build();

        String json =
                objectMapper.writeValueAsString(new Payload(9007199254740992L, LocalDateTime.of(2026, 8, 30, 12, 0)));

        assertThat(json).contains("\"id\":\"9007199254740992\"");
        assertThat(objectMapper.readTree(json).get("createdAt").isIntegralNumber())
                .isTrue();
    }

    @Test
    void moduleAndJsonUtilsBeans_useTheApplicationObjectMapper() {
        Module module = configuration.timestampSupportModuleBean();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(module);

        assertThat(module.getModuleName()).isEqualTo("TimestampSupportModule");
        assertThat(configuration.jsonUtils(objectMapper)).isNotNull();
        assertThat(JsonUtils.getObjectMapper()).isSameAs(objectMapper);
    }

    private record Payload(Long id, LocalDateTime createdAt) {}
}
