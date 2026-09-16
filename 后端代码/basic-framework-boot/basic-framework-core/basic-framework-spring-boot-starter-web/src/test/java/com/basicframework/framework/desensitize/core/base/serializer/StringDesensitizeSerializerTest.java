package com.basicframework.framework.desensitize.core.base.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.desensitize.core.regex.annotation.EmailDesensitize;
import com.basicframework.framework.desensitize.core.slider.annotation.CarLicenseDesensitize;
import com.basicframework.framework.desensitize.core.slider.annotation.ChineseNameDesensitize;
import com.basicframework.framework.desensitize.core.slider.annotation.FixedPhoneDesensitize;
import com.basicframework.framework.desensitize.core.slider.annotation.MobileDesensitize;
import com.basicframework.framework.desensitize.core.slider.annotation.PasswordDesensitize;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StringDesensitizeSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serialize_masksEachDeclaredSensitiveField() throws Exception {
        JsonNode result = objectMapper.readTree(objectMapper.writeValueAsString(new SensitivePayload()));

        assertThat(result.get("mobile").asText()).isEqualTo("138****5678");
        assertThat(result.get("password").asText()).isEqualTo("******");
        assertThat(result.get("email").asText()).isEqualTo("e****@example.com");
        assertThat(result.get("name").asText()).isEqualTo("刘**");
        assertThat(result.get("fixedPhone").asText()).isEqualTo("0108*****22");
        assertThat(result.get("carLicense").asText()).isEqualTo("粤A6***6");
        assertThat(result.get("blank").isNull()).isTrue();
    }

    private static final class SensitivePayload {

        @MobileDesensitize
        public String mobile = "13812345678";

        @PasswordDesensitize
        public String password = "secret";

        @EmailDesensitize
        public String email = "example@example.com";

        @ChineseNameDesensitize
        public String name = "刘子豪";

        @FixedPhoneDesensitize
        public String fixedPhone = "01086551122";

        @CarLicenseDesensitize
        public String carLicense = "粤A66666";

        @PasswordDesensitize
        public String blank = "";
    }
}
