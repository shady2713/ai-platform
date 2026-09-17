package com.basicframework.module.infra.api.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.api.file.dto.FileCreateReqDTO;
import com.basicframework.module.infra.api.file.dto.FileDeleteReqDTO;
import com.basicframework.module.infra.api.file.dto.FileReadReqDTO;
import com.basicframework.module.infra.api.file.dto.FileRespDTO;
import com.basicframework.module.infra.api.file.dto.FileSubjectDTO;
import com.basicframework.module.infra.dal.dataobject.file.FileDO;
import com.basicframework.module.infra.enums.file.FileAccessTypeEnum;
import com.basicframework.module.infra.service.file.FileAccessPrincipal;
import com.basicframework.module.infra.service.file.FileService;
import com.basicframework.module.infra.service.file.FileUploadPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 薄契约实现测试：DTO 到服务层参数的映射、元数据裁剪，以及"跨模块主体永远不具备管理权限"的约束。
 */
class FileApiImplTest {

    private static final Long USER_ID = 100L;

    private static final Integer USER_TYPE = 2;

    private final FileService fileService = mock(FileService.class);

    private final FileApiImpl api = new FileApiImpl(fileService);

    private static FileSubjectDTO subject() {
        FileSubjectDTO subject = new FileSubjectDTO();
        subject.setUserId(USER_ID);
        subject.setUserType(USER_TYPE);
        return subject;
    }

    private static FileDO businessFile() {
        return new FileDO()
                .setId(9L)
                .setConfigId(3L)
                .setPath("knowledge/2026-09-16/a.pdf")
                .setName("a.pdf")
                .setType("application/pdf")
                .setSize(12L)
                .setAccessType(FileAccessTypeEnum.PRIVATE.getValue())
                .setBusinessType("ai_knowledge_document")
                .setBusinessId(5L);
    }

    @Test
    void createFileDelegatesBusinessBindingAndSubject() {
        FileCreateReqDTO reqDTO = new FileCreateReqDTO();
        reqDTO.setContent(new byte[] {1, 2, 3});
        reqDTO.setName("a.pdf");
        reqDTO.setType("application/pdf");
        reqDTO.setBusinessType("ai_knowledge_document");
        reqDTO.setBusinessId(5L);
        reqDTO.setSubject(subject());
        when(fileService.createBusinessFile(
                        reqDTO.getContent(),
                        "a.pdf",
                        "application/pdf",
                        "ai_knowledge_document",
                        5L,
                        new FileUploadPrincipal(USER_ID, USER_TYPE)))
                .thenReturn(9L);

        assertThat(api.createFile(reqDTO)).isEqualTo(9L);
    }

    @Test
    void getFileMetaMapsOnlyContractFieldsAndDropsManagePermission() {
        when(fileService.getAuthorizedFile(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(businessFile());

        FileRespDTO resp = api.getFileMeta(readReq());

        assertThat(resp.getId()).isEqualTo(9L);
        assertThat(resp.getName()).isEqualTo("a.pdf");
        assertThat(resp.getType()).isEqualTo("application/pdf");
        assertThat(resp.getSize()).isEqualTo(12L);
        assertThat(resp.getBusinessType()).isEqualTo("ai_knowledge_document");
        assertThat(resp.getBusinessId()).isEqualTo(5L);

        ArgumentCaptor<FileAccessPrincipal> captor = ArgumentCaptor.forClass(FileAccessPrincipal.class);
        verify(fileService).getAuthorizedFile(org.mockito.ArgumentMatchers.eq(9L), captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().userType()).isEqualTo(USER_TYPE);
        // 跨模块调用方一律不具备管理权限：管理权限不得冒充业务授权
        assertThat(captor.getValue().canManageFiles()).isFalse();
    }

    @Test
    void getFileContentAuthorizesThenReadsThroughControlledPath() throws Exception {
        when(fileService.getAuthorizedFile(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(businessFile());
        when(fileService.getFileContent(
                        org.mockito.ArgumentMatchers.eq(3L),
                        org.mockito.ArgumentMatchers.eq("knowledge/2026-09-16/a.pdf"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new byte[] {7});

        assertThat(api.getFileContent(readReq())).containsExactly((byte) 7);

        verify(fileService)
                .getFileContent(
                        org.mockito.ArgumentMatchers.eq(3L),
                        org.mockito.ArgumentMatchers.eq("knowledge/2026-09-16/a.pdf"),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deleteFileDelegatesBusinessDelete() throws Exception {
        api.deleteFile(deleteReq());

        verify(fileService).deleteBusinessFile(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.any());
    }

    private static FileReadReqDTO readReq() {
        FileReadReqDTO reqDTO = new FileReadReqDTO();
        reqDTO.setFileId(9L);
        reqDTO.setSubject(subject());
        return reqDTO;
    }

    private static FileDeleteReqDTO deleteReq() {
        FileDeleteReqDTO reqDTO = new FileDeleteReqDTO();
        reqDTO.setFileId(9L);
        reqDTO.setSubject(subject());
        return reqDTO;
    }
}
