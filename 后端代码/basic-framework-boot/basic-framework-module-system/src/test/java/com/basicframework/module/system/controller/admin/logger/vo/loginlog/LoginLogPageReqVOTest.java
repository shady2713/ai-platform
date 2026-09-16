package com.basicframework.module.system.controller.admin.logger.vo.loginlog;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LoginLogPageReqVOTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsDatabaseColumnAndTimeRangeBoundaries() {
        LoginLogPageReqVO request = new LoginLogPageReqVO();
        request.setUserIp("i".repeat(50));
        request.setUsername("u".repeat(50));
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsColumnOverflowAndIncompleteTimeRange() {
        LoginLogPageReqVO request = new LoginLogPageReqVO();
        request.setUserIp("i".repeat(51));
        request.setUsername("u".repeat(51));
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now()});

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(invalidFields).containsExactlyInAnyOrder("userIp", "username", "createTime");
    }
}
