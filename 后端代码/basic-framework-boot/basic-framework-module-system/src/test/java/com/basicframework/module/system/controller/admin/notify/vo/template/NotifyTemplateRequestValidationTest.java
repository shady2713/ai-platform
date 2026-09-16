package com.basicframework.module.system.controller.admin.notify.vo.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.enums.notify.NotifyTemplateTypeEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NotifyTemplateRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void saveRequestRejectsUnknownEnumsAndDatabaseOverflow() {
        NotifyTemplateSaveReqVO request = validSaveRequest();
        assertThat(validator.validate(request)).isEmpty();

        request.setType(99);
        request.setStatus(99);
        request.setName("n".repeat(64));
        request.setCode("c".repeat(65));
        request.setContent("x".repeat(1025));

        assertThat(invalidFields(request)).contains("type", "status", "name", "code", "content");
    }

    @Test
    void pageRequestRequiresClosedEnumsAndTwoTimeEndpoints() {
        NotifyTemplatePageReqVO request = new NotifyTemplatePageReqVO();
        request.setType(99);
        request.setStatus(99);
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now()});

        assertThat(invalidFields(request)).containsExactlyInAnyOrder("type", "status", "createTime");
    }

    @Test
    void sendRequestBoundsParameterCountAndKeyShape() {
        NotifyTemplateSendReqVO request = new NotifyTemplateSendReqVO();
        request.setUserId(1L);
        request.setUserType(1);
        request.setTemplateCode("WELCOME");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int index = 0; index < 51; index++) {
            params.put("key" + index, index);
        }
        request.setTemplateParams(params);

        assertThat(invalidFields(request)).contains("templateParams");

        request.setTemplateParams(Map.of("", "value"));
        assertThat(invalidFields(request)).anyMatch(path -> path.startsWith("templateParams<K>"));
    }

    private static NotifyTemplateSaveReqVO validSaveRequest() {
        NotifyTemplateSaveReqVO request = new NotifyTemplateSaveReqVO();
        request.setName("欢迎通知");
        request.setCode("WELCOME");
        request.setType(NotifyTemplateTypeEnum.NOTIFICATION_MESSAGE.getType());
        request.setNickname("系统");
        request.setContent("你好 {name}");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }

    private Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
