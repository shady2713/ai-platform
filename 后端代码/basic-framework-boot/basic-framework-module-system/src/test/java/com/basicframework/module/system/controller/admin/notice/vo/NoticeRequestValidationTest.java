package com.basicframework.module.system.controller.admin.notice.vo;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.enums.notice.NoticeTypeEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class NoticeRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void saveRequestAcceptsTheTitleColumnBoundary() {
        NoticeSaveReqVO request = validSaveRequest();
        request.setTitle("n".repeat(50));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void saveRequestRejectsUnknownEnumsAndTitleOverflow() {
        NoticeSaveReqVO request = validSaveRequest();
        request.setTitle("n".repeat(51));
        request.setType(99);
        request.setStatus(99);

        assertThat(invalidFields(request)).containsExactlyInAnyOrder("title", "type", "status");
    }

    @Test
    void pageRequestRejectsUnknownStatusAndOversizedTitle() {
        NoticePageReqVO request = new NoticePageReqVO();
        request.setTitle("n".repeat(51));
        request.setStatus(99);

        assertThat(invalidFields(request)).containsExactlyInAnyOrder("title", "status");
    }

    private Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static NoticeSaveReqVO validSaveRequest() {
        NoticeSaveReqVO request = new NoticeSaveReqVO();
        request.setTitle("系统升级通知");
        request.setType(NoticeTypeEnum.NOTICE.getType());
        request.setContent("今晚升级");
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }
}
