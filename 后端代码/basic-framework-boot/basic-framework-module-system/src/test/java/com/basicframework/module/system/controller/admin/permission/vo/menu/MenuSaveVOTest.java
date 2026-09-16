package com.basicframework.module.system.controller.admin.permission.vo.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.common.enums.CommonStatusEnum;
import com.basicframework.module.system.enums.permission.MenuTypeEnum;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MenuSaveVOTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validBoundaryValuesPass() {
        MenuSaveVO request = validRequest();
        request.setIcon("i".repeat(100));
        request.setComponent("c".repeat(255));
        request.setComponentName("n".repeat(255));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsUnknownEnumsAndValuesLongerThanDatabaseColumns() {
        MenuSaveVO request = validRequest();
        request.setType(99);
        request.setStatus(99);
        request.setIcon("i".repeat(101));
        request.setComponent("c".repeat(256));
        request.setComponentName("n".repeat(256));

        Set<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());

        assertThat(invalidFields).containsExactlyInAnyOrder("type", "status", "icon", "component", "componentName");
    }

    private static MenuSaveVO validRequest() {
        MenuSaveVO request = new MenuSaveVO();
        request.setName("用户管理");
        request.setType(MenuTypeEnum.MENU.getType());
        request.setSort(1);
        request.setParentId(0L);
        request.setStatus(CommonStatusEnum.ENABLE.getStatus());
        return request;
    }
}
