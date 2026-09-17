package com.basicframework.module.infra.api.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.infra.api.file.dto.FileBusinessAccessContext;
import com.basicframework.module.infra.api.file.dto.FileCreateReqDTO;
import com.basicframework.module.infra.api.file.dto.FileDeleteReqDTO;
import com.basicframework.module.infra.api.file.dto.FileReadReqDTO;
import com.basicframework.module.infra.api.file.dto.FileRespDTO;
import com.basicframework.module.infra.api.file.dto.FileSubjectDTO;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 文件薄契约与业务授权 SPI 的契约测试：钉住方法签名与"默认拒绝"语义。
 */
class FileCommonApiTest {

    private static FileSubjectDTO subject() {
        FileSubjectDTO subject = new FileSubjectDTO();
        subject.setUserType(2);
        subject.setUserId(1024L);
        return subject;
    }

    @Test
    void shouldDelegateCreateReadAndDelete() throws Exception {
        AtomicReference<FileCreateReqDTO> created = new AtomicReference<>();
        AtomicReference<Long> readId = new AtomicReference<>();
        AtomicReference<Long> deletedId = new AtomicReference<>();
        FileCreateReqDTO createReq = new FileCreateReqDTO();
        createReq.setBusinessType("ai_knowledge_document");
        createReq.setBusinessId(8L);
        createReq.setContent(new byte[] {1, 2});
        createReq.setSubject(subject());
        FileReadReqDTO readReq = new FileReadReqDTO();
        readReq.setFileId(7L);
        readReq.setSubject(subject());
        FileDeleteReqDTO deleteReq = new FileDeleteReqDTO();
        deleteReq.setFileId(9L);
        deleteReq.setSubject(subject());

        FileCommonApi api = new FileCommonApi() {
            @Override
            public Long createFile(FileCreateReqDTO reqDTO) {
                created.set(reqDTO);
                return 66L;
            }

            @Override
            public FileRespDTO getFileMeta(FileReadReqDTO reqDTO) {
                readId.set(reqDTO.getFileId());
                return new FileRespDTO();
            }

            @Override
            public byte[] getFileContent(FileReadReqDTO reqDTO) {
                readId.set(reqDTO.getFileId());
                return new byte[] {9};
            }

            @Override
            public void deleteFile(FileDeleteReqDTO reqDTO) {
                deletedId.set(reqDTO.getFileId());
            }
        };

        assertThat(api.createFile(createReq)).isEqualTo(66L);
        assertThat(api.getFileMeta(readReq)).isNotNull();
        assertThat(api.getFileContent(readReq)).containsExactly((byte) 9);
        api.deleteFile(deleteReq);

        assertThat(created).hasValue(createReq);
        assertThat(readId).hasValue(7L);
        assertThat(deletedId).hasValue(9L);
    }

    @Test
    void shouldDenyDeleteByDefaultOnBusinessAccessProvider() {
        FileBusinessAccessProvider provider = new FileBusinessAccessProvider() {
            @Override
            public String getBusinessType() {
                return "ai_knowledge_document";
            }

            @Override
            public boolean canRead(FileBusinessAccessContext context) {
                return context.getBusinessId() != null;
            }
        };

        FileBusinessAccessContext context = new FileBusinessAccessContext();
        context.setFileId(1L);
        context.setBusinessType(provider.getBusinessType());
        context.setBusinessId(2L);
        context.setSubject(subject());

        assertThat(provider.canRead(context)).isTrue();
        // 默认拒绝删除：业务文件删除必须由业务模块显式实现
        assertThat(provider.canDelete(context)).isFalse();
    }

    @Test
    void shouldExposeDtoFieldsForContractConsumers() {
        FileSubjectDTO subject = subject();
        FileCreateReqDTO createReq = new FileCreateReqDTO();
        createReq.setName("knowledge.pdf");
        createReq.setType("application/pdf");
        createReq.setBusinessType("ai_knowledge_document");
        createReq.setBusinessId(3L);
        createReq.setSubject(subject);
        createReq.setContent(new byte[] {3});

        assertThat(createReq.getName()).isEqualTo("knowledge.pdf");
        assertThat(createReq.getType()).isEqualTo("application/pdf");
        assertThat(createReq.getBusinessType()).isEqualTo("ai_knowledge_document");
        assertThat(createReq.getBusinessId()).isEqualTo(3L);
        assertThat(createReq.getContent()).containsExactly((byte) 3);
        assertThat(subject.getUserId()).isEqualTo(1024L);
        assertThat(subject.getUserType()).isEqualTo(2);

        FileRespDTO resp = new FileRespDTO();
        resp.setId(5L);
        resp.setName("a.pdf");
        resp.setType("application/pdf");
        resp.setSize(10L);
        resp.setBusinessType("ai_knowledge_document");
        resp.setBusinessId(6L);
        assertThat(resp.getId()).isEqualTo(5L);
        assertThat(resp.getName()).isEqualTo("a.pdf");
        assertThat(resp.getType()).isEqualTo("application/pdf");
        assertThat(resp.getSize()).isEqualTo(10L);
        assertThat(resp.getBusinessType()).isEqualTo("ai_knowledge_document");
        assertThat(resp.getBusinessId()).isEqualTo(6L);

        FileBusinessAccessContext context = new FileBusinessAccessContext();
        context.setSubject(subject);
        assertThat(context.getSubject()).isSameAs(subject);
    }
}
