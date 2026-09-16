package com.basicframework.module.system.controller.admin.user.vo;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.controller.admin.auth.vo.AuthResetPasswordReqVO;
import com.basicframework.module.system.controller.admin.user.vo.profile.UserProfileUpdateReqVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserSaveReqVO;
import com.basicframework.module.system.controller.admin.user.vo.user.UserUpdatePasswordReqVO;
import com.basicframework.module.system.service.user.dto.UserImportDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class UserRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void createUser_acceptsContractCompliantFields() {
        UserSaveReqVO request = validCreateRequest();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void createUser_rejectsInvalidNicknameRemarkAndEnums() {
        UserSaveReqVO request = validCreateRequest();
        request.setNickname("管理员\n越权");
        request.setRemark("😀".repeat(501));
        request.setSex(99);
        request.setStatus(99);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("nickname", "remark", "sex", "status");
    }

    @Test
    void allPasswordSettingRequests_rejectShortPasswords() {
        UserUpdatePasswordReqVO adminReset = new UserUpdatePasswordReqVO();
        adminReset.setId(1L);
        adminReset.setPassword("short-password");
        AuthResetPasswordReqVO smsReset = AuthResetPasswordReqVO.builder()
                .password("short-password")
                .mobile("13812345678")
                .code("123456")
                .build();

        assertThat(validator.validate(adminReset))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("password");
        assertThat(validator.validate(smsReset))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("password");
    }

    @Test
    void profile_rejectsBlankOrUnsafeNicknameAndUnknownSex() {
        UserProfileUpdateReqVO request = new UserProfileUpdateReqVO();
        request.setNickname(" ");
        request.setSex(99);

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("nickname", "sex");
    }

    private static UserSaveReqVO validCreateRequest() {
        UserSaveReqVO request = new UserSaveReqVO();
        request.setUsername("alice_01");
        request.setNickname("Alice😀");
        request.setPassword("violet river orbits quietly!");
        request.setMobile("13812345678");
        request.setEmail("Alice@Example.COM");
        request.setSex(1);
        request.setStatus(0);
        return request;
    }

    @Test
    void importedUsersFollowTheSameNicknameAndEnumContract() {
        UserImportDTO row = new UserImportDTO()
                .setUsername("alice_01")
                .setNickname("Alice😀")
                .setSex(1)
                .setStatus(0);
        assertThat(validator.validate(row)).isEmpty();
        row.setNickname(" ").setSex(99).setStatus(99);
        assertThat(validator.validate(row))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("nickname", "sex", "status");
        row.setNickname("bad\nname");
        assertThat(validator.validate(row))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("nickname");
    }
}
