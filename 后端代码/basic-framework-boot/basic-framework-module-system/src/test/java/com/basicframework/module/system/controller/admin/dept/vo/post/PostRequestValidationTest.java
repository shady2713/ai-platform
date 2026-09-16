package com.basicframework.module.system.controller.admin.dept.vo.post;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PostRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void saveRequestAcceptsDatabaseBoundaries() {
        PostSaveReqVO request = validSaveRequest();
        request.setName("n".repeat(50));
        request.setCode("c".repeat(64));
        request.setRemark("r".repeat(500));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void saveRequestRejectsMissingStatusNegativeSortAndColumnOverflow() {
        PostSaveReqVO request = validSaveRequest();
        request.setStatus(null);
        request.setSort(-1);
        request.setRemark("r".repeat(501));

        assertThat(invalidFields(request)).containsExactlyInAnyOrder("status", "sort", "remark");
    }

    @Test
    void pageRequestRejectsUnknownStatusAndOversizedFilters() {
        PostPageReqVO request = new PostPageReqVO();
        request.setCode("c".repeat(65));
        request.setName("n".repeat(51));
        request.setStatus(99);

        assertThat(invalidFields(request)).containsExactlyInAnyOrder("code", "name", "status");
    }

    private Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static PostSaveReqVO validSaveRequest() {
        PostSaveReqVO request = new PostSaveReqVO();
        request.setName("技术负责人");
        request.setCode("TECH_LEAD");
        request.setSort(0);
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }
}
