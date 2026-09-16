package com.basicframework.module.infra.controller.admin.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.enums.UserTypeEnum;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.service.SecurityFrameworkService;
import com.basicframework.module.infra.controller.admin.file.vo.file.FileCreateReqVO;
import com.basicframework.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import com.basicframework.module.infra.controller.admin.file.vo.file.FilePresignedUrlRespVO;
import com.basicframework.module.infra.controller.admin.file.vo.file.FileRespVO;
import com.basicframework.module.infra.controller.admin.file.vo.file.FileUploadReqVO;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.enums.ErrorCodeConstants;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.service.file.FileAccessPrincipal;
import com.basicframework.module.infra.service.file.FileService;
import com.basicframework.module.infra.service.file.FileUploadPrincipal;
import com.basicframework.module.infra.service.file.dto.FilePresignedUrlDTO;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** {@link FileController} 下载行为测试：不存在的文件统一走 404 JSON 错误体（ADR 0003）。 */
class FileControllerTest {

    private final FileService fileService = mock(FileService.class);
    private final SecurityFrameworkService securityFrameworkService = mock(SecurityFrameworkService.class);
    private final FileController controller = new FileController(fileService, securityFrameworkService);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void uploadFile_defaultsToPrivateReadAndBindsTheAdminPrincipal() throws Exception {
        FileUploadReqVO request = new FileUploadReqVO();
        request.setDirectory("reports");
        request.setFile(new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[] {1, 2, 3}));
        FileUploadPrincipal principal = adminPrincipal();
        bindAdminRequest();
        when(fileService.createFile(
                        any(byte[].class),
                        eq("report.pdf"),
                        eq("reports"),
                        eq("application/pdf"),
                        eq(principal),
                        eq(FileAccessTypeEnum.PRIVATE)))
                .thenReturn("https://files.example.com/private/report.pdf");

        CommonResult<String> result = controller.uploadFile(request);

        assertThat(result.getData()).isEqualTo("https://files.example.com/private/report.pdf");
        verify(fileService)
                .createFile(
                        any(byte[].class),
                        eq("report.pdf"),
                        eq("reports"),
                        eq("application/pdf"),
                        eq(principal),
                        eq(FileAccessTypeEnum.PRIVATE));
    }

    @Test
    void presignedUploadAndCreateBindTheAdminPrincipalAndExplicitReadPolicy() {
        FileUploadPrincipal principal = adminPrincipal();
        String uploadToken = "a".repeat(64);
        bindAdminRequest();
        FilePresignedUrlDTO presignedUrl = new FilePresignedUrlDTO(
                1L,
                "https://upload.example.com/reports/report.pdf",
                "https://files.example.com/reports/report.pdf",
                "reports/report.pdf",
                uploadToken);
        when(fileService.presignPutUrl(
                        "report.pdf", "reports", 12L, "application/pdf", principal, FileAccessTypeEnum.PUBLIC))
                .thenReturn(presignedUrl);
        when(fileService.createPresignedFile(uploadToken, principal))
                .thenReturn("https://files.example.com/reports/report.pdf");
        FileCreateReqVO createRequest = new FileCreateReqVO();
        createRequest.setUploadToken(uploadToken);

        CommonResult<FilePresignedUrlRespVO> presignedResult =
                controller.getFilePresignedUrl("report.pdf", 12L, "application/pdf", "reports", true);
        CommonResult<String> createResult = controller.createFile(createRequest);

        assertThat(presignedResult.getData().getUploadUrl()).isEqualTo(presignedUrl.getUploadUrl());
        assertThat(presignedResult.getData().getUploadToken()).isEqualTo(uploadToken);
        assertThat(createResult.getData()).isEqualTo("https://files.example.com/reports/report.pdf");
        verify(fileService)
                .presignPutUrl("report.pdf", "reports", 12L, "application/pdf", principal, FileAccessTypeEnum.PUBLIC);
        verify(fileService).createPresignedFile(uploadToken, principal);
    }

    @Test
    void managementEndpoints_mapFilesAndDelegateMutations() throws Exception {
        FileDO file = new FileDO()
                .setId(1L)
                .setConfigId(2L)
                .setName("report.pdf")
                .setPath("reports/report.pdf")
                .setUrl("https://files.example.com/reports/report.pdf")
                .setType("application/pdf")
                .setSize(12L)
                .setAccessType(FileAccessTypeEnum.PRIVATE.getValue());
        FilePageReqVO pageRequest = new FilePageReqVO();
        pageRequest.setPath("report");
        pageRequest.setType("application/pdf");
        LocalDateTime[] createTime = {LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 31, 23, 59)};
        pageRequest.setCreateTime(createTime);
        when(fileService.getFile(1L)).thenReturn(file);
        when(fileService.getFilePage(pageRequest, "report", "application/pdf", createTime))
                .thenReturn(new PageResult<>(List.of(file), 1L));

        CommonResult<FileRespVO> getResult = controller.getFile(1L);
        CommonResult<Boolean> deleteResult = controller.deleteFile(1L);
        CommonResult<Boolean> deleteListResult = controller.deleteFileList(List.of(1L, 2L));
        CommonResult<PageResult<FileRespVO>> pageResult = controller.getFilePage(pageRequest);

        assertThat(getResult.getData().getPath()).isEqualTo("reports/report.pdf");
        assertThat(deleteResult.getData()).isTrue();
        assertThat(deleteListResult.getData()).isTrue();
        assertThat(pageResult.getData().getTotal()).isEqualTo(1L);
        assertThat(pageResult.getData().getList()).singleElement().satisfies(item -> assertThat(item.getAccessType())
                .isEqualTo(FileAccessTypeEnum.PRIVATE.getValue()));
        verify(fileService).deleteFile(1L);
        verify(fileService).deleteFileList(List.of(1L, 2L));
    }

    @Test
    void getFileContent_missingFile_throwsNotExistsForGlobalHandler() throws Exception {
        MockHttpServletRequest request = fileRequest("/admin-api/infra/file/1/get/a/b.png");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(fileService.getFileContent(1L, "a/b.png", new FileAccessPrincipal(null, 2, false)))
                .thenReturn(null);

        assertThatThrownBy(() -> controller.getFileContent(request, response, 1L))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(ErrorCodeConstants.FILE_NOT_EXISTS.getCode()));
    }

    @Test
    void getFileContent_emptyPath_rejectsAsInvalid() {
        MockHttpServletRequest request = fileRequest("/admin-api/infra/file/1/get/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> controller.getFileContent(request, response, 1L))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(ErrorCodeConstants.FILE_PATH_INVALID.getCode()));
    }

    @Test
    void getFileContent_existingFile_writesAttachment() throws Exception {
        byte[] png = Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        MockHttpServletRequest request = fileRequest("/admin-api/infra/file/1/get/a/b.png");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(fileService.getFileContent(1L, "a/b.png", new FileAccessPrincipal(null, 2, false)))
                .thenReturn(png);

        controller.getFileContent(request, response, 1L);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEqualTo(png);
    }

    @Test
    void getFileContent_memberNeverReceivesAdminFilePermission() throws Exception {
        MockHttpServletRequest request = fileRequest("/app-api/infra/file/1/get/private.png");
        request.setAttribute("login_user_id", 7L);
        request.setAttribute("login_user_type", 1);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FileAccessPrincipal principal = new FileAccessPrincipal(7L, 1, false);
        when(securityFrameworkService.hasPermission("infra:file:query")).thenReturn(true);
        when(fileService.getFileContent(1L, "private.png", principal)).thenReturn(null);

        assertThatThrownBy(() -> controller.getFileContent(request, response, 1L))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(ErrorCodeConstants.FILE_NOT_EXISTS.getCode()));
        verify(fileService).getFileContent(1L, "private.png", principal);
    }

    @Test
    void getFileContent_adminWithFilePermissionCanReadPrivateContent() throws Exception {
        byte[] content = {1, 2, 3};
        MockHttpServletRequest request = fileRequest("/admin-api/infra/file/1/get/private.png");
        request.setAttribute("login_user_id", 7L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FileAccessPrincipal principal = new FileAccessPrincipal(7L, UserTypeEnum.ADMIN.getValue(), true);
        when(securityFrameworkService.hasPermission("infra:file:query")).thenReturn(true);
        when(fileService.getFileContent(1L, "private.png", principal)).thenReturn(content);

        controller.getFileContent(request, response, 1L);

        assertThat(response.getContentAsByteArray()).isEqualTo(content);
        verify(fileService).getFileContent(1L, "private.png", principal);
    }

    private static MockHttpServletRequest fileRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setAttribute("login_user_type", UserTypeEnum.ADMIN.getValue());
        return request;
    }

    private static FileUploadPrincipal adminPrincipal() {
        return new FileUploadPrincipal(7L, UserTypeEnum.ADMIN.getValue());
    }

    private static void bindAdminRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("login_user_id", 7L);
        request.setAttribute("login_user_type", UserTypeEnum.ADMIN.getValue());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
