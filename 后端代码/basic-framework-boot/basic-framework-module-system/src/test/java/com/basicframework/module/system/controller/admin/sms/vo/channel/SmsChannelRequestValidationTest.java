package com.basicframework.module.system.controller.admin.sms.vo.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SmsChannelRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void createRequiresBothCredentialsButUpdateMayKeepStoredValues() {
        SmsChannelSaveReqVO request = validSaveRequest();

        assertThat(validator.validate(request)).isEmpty();

        request.setApiKey(null);
        assertThat(invalidFields(request)).contains("credentialsValid");

        request.setId(1L);
        request.setApiSecret(null);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsUnknownEnumsOversizedFieldsAndUnsafeCallbackScheme() {
        SmsChannelSaveReqVO request = validSaveRequest();
        request.setCode("UNKNOWN");
        request.setStatus(99);
        request.setSignature("签".repeat(13));
        request.setCallbackUrl("ftp://example.test/callback");

        assertThat(invalidFields(request)).contains("code", "status", "signature", "callbackUrl");
    }

    @Test
    void pageFilterRequiresClosedEnumsAndTwoTimeEndpoints() {
        SmsChannelPageReqVO request = new SmsChannelPageReqVO();
        request.setCode("UNKNOWN");
        request.setStatus(99);
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now()});

        assertThat(invalidFields(request)).containsExactlyInAnyOrder("code", "status", "createTime");
    }

    private static SmsChannelSaveReqVO validSaveRequest() {
        SmsChannelSaveReqVO request = new SmsChannelSaveReqVO();
        request.setSignature("基础框架");
        request.setCode("ALIYUN");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        request.setApiKey("provider-account");
        request.setApiSecret("provider-secret");
        request.setCallbackUrl("https://example.test/callback");
        return request;
    }

    private Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
