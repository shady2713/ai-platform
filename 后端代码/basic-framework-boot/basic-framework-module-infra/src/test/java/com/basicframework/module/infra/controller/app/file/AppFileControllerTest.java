package com.basicframework.module.infra.controller.app.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.infra.controller.app.file.vo.AppFileCreateReqVO;
import com.basicframework.module.infra.controller.app.file.vo.AppFilePresignedUrlRespVO;
import com.basicframework.module.infra.controller.app.file.vo.AppFileUploadReqVO;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.service.file.FileService;
import com.basicframework.module.infra.service.file.FileUploadPrincipal;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AppFileControllerTest {

    private static final Long MEMBER_ID = 23L;
    private static final Integer MEMBER_TYPE = UserTypeEnum.MEMBER.getValue();

    private final FileService fileService = mock(FileService.class);
    private final AppFileController controller = new AppFileController(fileService);
    private final FileUploadPrincipal principal = new FileUploadPrincipal(MEMBER_ID, MEMBER_TYPE);

    @BeforeEach
    void bindMemberRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("login_user_id", MEMBER_ID);
        request.setAttribute("login_user_type", MEMBER_TYPE);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void uploadFile_defaultsToPrivateReadAndKeepsAuthenticatedOwner() throws Exception {
        AppFileUploadReqVO request = new AppFileUploadReqVO();
        request.setDirectory("avatars");
        request.setFile(
                new MockMultipartFile("file", "profile.png", "image/png", "profile".getBytes(StandardCharsets.UTF_8)));
        when(fileService.createFile(
                        any(byte[].class),
                        eq("profile.png"),
                        eq("avatars"),
                        eq("image/png"),
                        eq(principal),
                        eq(FileAccessTypeEnum.PRIVATE)))
                .thenReturn("https://files.example.com/private/profile.png");

        CommonResult<String> result = controller.uploadFile(request);

        assertThat(result.getData()).isEqualTo("https://files.example.com/private/profile.png");
        verify(fileService)
                .createFile(
                        any(byte[].class),
                        eq("profile.png"),
                        eq("avatars"),
                        eq("image/png"),
                        eq(principal),
                        eq(FileAccessTypeEnum.PRIVATE));
    }

    @Test
    void getFilePresignedUrl_passesExplicitPublicReadToTheService() {
        FilePresignedUrlDTO presignedUrl = new FilePresignedUrlDTO(
                7L,
                "https://upload.example.com/avatars/profile.png",
                "https://files.example.com/avatars/profile.png",
                "avatars/profile.png",
                "a".repeat(64));
        when(fileService.presignPutUrl(
                        "profile.png", "avatars", 12L, "image/png", principal, FileAccessTypeEnum.PUBLIC))
                .thenReturn(presignedUrl);

        CommonResult<AppFilePresignedUrlRespVO> result =
                controller.getFilePresignedUrl("profile.png", 12L, "image/png", "avatars", true);

        assertThat(result.getData().getUploadUrl()).isEqualTo(presignedUrl.getUploadUrl());
        assertThat(result.getData().getUploadToken()).isEqualTo(presignedUrl.getUploadToken());
    }

    @Test
    void createFile_bindsPresignedUploadToTheAuthenticatedOwner() {
        String uploadToken = "b".repeat(64);
        AppFileCreateReqVO request = new AppFileCreateReqVO();
        request.setUploadToken(uploadToken);
        when(fileService.createPresignedFile(uploadToken, principal))
                .thenReturn("https://files.example.com/avatars/profile.png");

        CommonResult<String> result = controller.createFile(request);

        assertThat(result.getData()).isEqualTo("https://files.example.com/avatars/profile.png");
        verify(fileService).createPresignedFile(uploadToken, principal);
    }
}
