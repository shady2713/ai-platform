package com.basicframework.module.system.controller.admin.logger.vo.operatelog;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OperateLogPageReqVOTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsDatabaseColumnAndTimeRangeBoundaries() {
        OperateLogPageReqVO request = new OperateLogPageReqVO();
        request.setUserId(1L);
        request.setBizId(1L);
        request.setType("t".repeat(50));
        request.setSubType("s".repeat(50));
        request.setAction("a".repeat(2000));
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now().minusDays(1), LocalDateTime.now()});

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsInvalidIdsColumnOverflowAndIncompleteTimeRange() {
        OperateLogPageReqVO request = new OperateLogPageReqVO();
        request.setUserId(0L);
        request.setBizId(-1L);
        request.setType("t".repeat(51));
        request.setSubType("s".repeat(51));
        request.setAction("a".repeat(2001));
        request.setCreateTime(new LocalDateTime[] {LocalDateTime.now()});

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(invalidFields)
                .containsExactlyInAnyOrder("userId", "bizId", "type", "subType", "action", "createTime");
    }
}
