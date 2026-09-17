package com.basicframework.module.infra.service.file;

import static com.basicframework.module.infra.testutil.ServiceExceptionAssert.assertServiceException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.infra.api.file.FileBusinessAccessProvider;
import com.basicframework.module.infra.api.file.dto.FileBusinessAccessContext;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.dal.mysql.file.FileMapper;
import com.basicframework.module.infra.enums.ErrorCodeConstants;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.framework.file.config.FileArchiveSecurityProperties;
import com.basicframework.module.infra.framework.file.core.utils.FileArchiveValidator;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * 业务文件授权边界：业务绑定文件的读写删由业务授权 Provider 决定，管理权限不得冒充授权；
 * 未注册业务类型一律拒绝（fail-closed）；无业务绑定的旧文件保持既有规则。
 */
class FileBusinessAuthorizationTest {

    private static final String BUSINESS_TYPE = "ai_knowledge_document";

    private static final Long AUTHORIZED_USER_ID = 100L;

    private static final FileUploadPrincipal UPLOAD_PRINCIPAL = new FileUploadPrincipal(AUTHORIZED_USER_ID, 2);

    private final FileConfigService fileConfigService = mock(FileConfigService.class);

    private final FileMapper fileMapper = mock(FileMapper.class);

    private final FileArchiveValidator fileArchiveValidator =
            new FileArchiveValidator(new FileArchiveSecurityProperties());

    private final FileDeletionService fileDeletionService = mock(FileDeletionService.class);

    private final FilePresignedUploadService filePresignedUploadService = mock(FilePresignedUploadService.class);

    private final FileBusinessAccessProvider provider = new FileBusinessAccessProvider() {
        @Override
        public String getBusinessType() {
            return BUSINESS_TYPE;
        }

        @Override
        public boolean canRead(FileBusinessAccessContext context) {
            return Objects.equals(context.getSubject().getUserId(), AUTHORIZED_USER_ID);
        }

        @Override
        public boolean canDelete(FileBusinessAccessContext context) {
            return Objects.equals(context.getSubject().getUserId(), AUTHORIZED_USER_ID);
        }
    };

    private final FileServiceImpl fileService = new FileServiceImpl(
            fileConfigService,
            fileMapper,
            fileArchiveValidator,
            fileDeletionService,
            filePresignedUploadService,
            new FileBusinessAccessProviderRegistry(List.of(provider)));

    private final FileServiceImpl fileServiceWithoutProvider = new FileServiceImpl(
            fileConfigService,
            fileMapper,
            fileArchiveValidator,
            fileDeletionService,
            filePresignedUploadService,
            new FileBusinessAccessProviderRegistry(List.of()));

    private static FileDO businessBoundFile() {
        return new FileDO()
                .setId(1L)
                .setConfigId(1L)
                .setPath("knowledge/2026-09-16/a.pdf")
                .setName("a.pdf")
                .setType("application/pdf")
                .setSize(10L)
                .setAccessType(FileAccessTypeEnum.PRIVATE.getValue())
                .setBusinessType(BUSINESS_TYPE)
                .setBusinessId(5L)
                .setOwnerUserId(7L)
                .setOwnerUserType(2);
    }

    @Test
    void registryRejectsBlankBusinessType() {
        FileBusinessAccessProvider blank = new FileBusinessAccessProvider() {
            @Override
            public String getBusinessType() {
                return "  ";
            }

            @Override
            public boolean canRead(FileBusinessAccessContext context) {
                return true;
            }
        };

        assertThatThrownBy(() -> new FileBusinessAccessProviderRegistry(List.of(blank)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须声明业务类型");
    }

    @Test
    void registryRejectsDuplicateBusinessType() {
        assertThatThrownBy(() -> new FileBusinessAccessProviderRegistry(List.of(provider, provider)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存在重复的文件授权 Provider");
    }

    @Test
    void registryDeniesUnknownBusinessType() {
        assertThat(new FileBusinessAccessProviderRegistry(List.of()).isRegistered(BUSINESS_TYPE))
                .isFalse();
        assertServiceException(
                ErrorCodeConstants.FILE_BUSINESS_TYPE_UNREGISTERED,
                () -> new FileBusinessAccessProviderRegistry(List.of()).requireRegistered(BUSINESS_TYPE));
    }

    @Test
    void authorizedSubjectCanReadBusinessFile() {
        when(fileMapper.selectActiveById(1L)).thenReturn(businessBoundFile());

        FileDO file = fileService.getAuthorizedFile(1L, new FileAccessPrincipal(AUTHORIZED_USER_ID, 2, false));

        assertThat(file.getBusinessType()).isEqualTo(BUSINESS_TYPE);
    }

    @Test
    void unauthorizedSubjectCannotReadBusinessFile() {
        when(fileMapper.selectActiveById(1L)).thenReturn(businessBoundFile());

        assertServiceException(
                ErrorCodeConstants.FILE_NOT_EXISTS,
                () -> fileService.getAuthorizedFile(1L, new FileAccessPrincipal(999L, 2, false)));
    }

    @Test
    void managerPermissionCannotSubstituteBusinessAuthorization() {
        when(fileMapper.selectActiveById(1L)).thenReturn(businessBoundFile());

        // 管理权限（canManageFiles=true）与所有者身份都不构成业务绑定的豁免
        assertServiceException(
                ErrorCodeConstants.FILE_NOT_EXISTS,
                () -> fileService.getAuthorizedFile(1L, new FileAccessPrincipal(999L, 1, true)));
        assertServiceException(
                ErrorCodeConstants.FILE_NOT_EXISTS,
                () -> fileService.getAuthorizedFile(1L, new FileAccessPrincipal(7L, 2, false)));
    }

    @Test
    void businessFileIsDeniedWhenProviderNotRegistered() {
        when(fileMapper.selectActiveById(1L)).thenReturn(businessBoundFile());

        assertServiceException(
                ErrorCodeConstants.FILE_NOT_EXISTS,
                () -> fileServiceWithoutProvider.getAuthorizedFile(
                        1L, new FileAccessPrincipal(AUTHORIZED_USER_ID, 2, false)));
    }

    @Test
    void filesWithoutBusinessBindingKeepExistingRules() {
        FileDO publicFile = new FileDO().setId(2L).setAccessType(FileAccessTypeEnum.PUBLIC.getValue());
        FileDO ownedFile = new FileDO()
                .setId(3L)
                .setAccessType(FileAccessTypeEnum.PRIVATE.getValue())
                .setOwnerUserId(7L)
                .setOwnerUserType(2);
        when(fileMapper.selectActiveById(2L)).thenReturn(publicFile);
        when(fileMapper.selectActiveById(3L)).thenReturn(ownedFile);

        assertThat(fileService.getAuthorizedFile(2L, null).getId()).isEqualTo(2L);
        assertThat(fileService
                        .getAuthorizedFile(3L, new FileAccessPrincipal(7L, 2, false))
                        .getId())
                .isEqualTo(3L);
        assertThat(fileService
                        .getAuthorizedFile(3L, new FileAccessPrincipal(8L, 2, true))
                        .getId())
                .isEqualTo(3L);
    }

    @Test
    void creatingFileWithUnregisteredBusinessTypeIsRejected() {
        assertServiceException(
                ErrorCodeConstants.FILE_BUSINESS_TYPE_UNREGISTERED,
                () -> fileServiceWithoutProvider.createBusinessFile(
                        new byte[] {1}, "a.pdf", "application/pdf", BUSINESS_TYPE, 5L, UPLOAD_PRINCIPAL));
        verifyNoInteractions(filePresignedUploadService);
    }

    @Test
    void adminDeleteCannotRemoveBusinessFile() {
        when(fileMapper.selectActiveById(1L)).thenReturn(businessBoundFile());

        assertThatThrownBy(() -> fileService.deleteFile(1L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("业务绑定文件必须在业务模块内删除");
        verifyNoInteractions(fileDeletionService);
    }

    @Test
    void businessDeleteRequiresCanDeleteAuthorization() throws Exception {
        when(fileMapper.selectActiveById(1L)).thenReturn(businessBoundFile());

        assertThatThrownBy(() -> fileService.deleteBusinessFile(1L, new FileAccessPrincipal(999L, 2, false)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("文件不存在");

        fileService.deleteBusinessFile(1L, new FileAccessPrincipal(AUTHORIZED_USER_ID, 2, false));
        verify(fileDeletionService).deleteFiles(List.of(1L));
    }
}
