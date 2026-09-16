package com.basicframework.module.infra.controller.app.file.vo;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AppFileRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void uploadRequest_acceptsRelativeDirectoryAndRejectsTraversal() {
        AppFileUploadReqVO request = new AppFileUploadReqVO();
        request.setFile(new MockMultipartFile("file", "avatar.png", "image/png", new byte[] {1}));
        request.setDirectory("member/avatar");
        assertThat(validator.validate(request)).isEmpty();

        request.setDirectory("../admin");
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("directoryValid");
    }

    @Test
    void createRequest_acceptsOnlyWellFormedOneTimeToken() {
        AppFileCreateReqVO request = new AppFileCreateReqVO();
        request.setUploadToken("invalid");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsOnly("uploadToken");

        request.setUploadToken("a".repeat(64));
        assertThat(validator.validate(request)).isEmpty();
    }
}
