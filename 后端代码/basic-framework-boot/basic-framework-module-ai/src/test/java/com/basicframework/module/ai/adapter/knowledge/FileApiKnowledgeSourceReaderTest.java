package com.basicframework.module.ai.adapter.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.infra.api.file.FileCommonApi;
import com.basicframework.module.infra.api.file.dto.FileReadReqDTO;
import com.basicframework.module.infra.api.file.dto.FileRespDTO;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** K05 原文读取适配器：主体缺失即失败（fail-closed）、读取失败与不存在同语义、不泄内容。 */
class FileApiKnowledgeSourceReaderTest {

    private final FileCommonApi fileCommonApi = mock(FileCommonApi.class);

    private final FileApiKnowledgeSourceReader reader = new FileApiKnowledgeSourceReader(fileCommonApi);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(Long userId) {
        LoginUser loginUser = new LoginUser().setId(userId).setUserType(2);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private static void assertReason(Throwable throwable, KnowledgeSourceException.Reason expected) {
        assertThat(throwable).isInstanceOf(KnowledgeSourceException.class);
        assertThat(((KnowledgeSourceException) throwable).reason()).isEqualTo(expected);
    }

    @Test
    void readsContentAndFileNameUnderTheCurrentSubject() {
        loginAs(41L);
        byte[] content = "华东区域净额".getBytes(StandardCharsets.UTF_8);
        when(fileCommonApi.getFileContent(any(FileReadReqDTO.class))).thenReturn(content);
        when(fileCommonApi.getFileMeta(any(FileReadReqDTO.class)))
                .thenReturn(new FileRespDTO().setName("handbook.txt"));

        var source = reader.read(501L);

        assertThat(source.content()).isEqualTo(content);
        assertThat(source.fileName()).isEqualTo("handbook.txt");
    }

    @Test
    void backgroundThreadWithoutSubjectFailsClosed() {
        assertThatThrownBy(() -> reader.read(501L))
                .as("没有主体上下文时不能以系统身份读文件")
                .satisfies(throwable -> assertReason(throwable, KnowledgeSourceException.Reason.SUBJECT_MISSING));
        assertThatThrownBy(() -> reader.read(null))
                .satisfies(throwable -> assertReason(throwable, KnowledgeSourceException.Reason.NOT_ACCESSIBLE));
    }

    @Test
    void missingOrUnauthorizedOrEmptyFileIsIndistinguishable() {
        loginAs(41L);
        when(fileCommonApi.getFileContent(any(FileReadReqDTO.class)))
                .thenThrow(new IllegalStateException("no permission or not exists"));
        assertThatThrownBy(() -> reader.read(501L))
                .as("无权限与不存在同语义（防枚举）")
                .satisfies(throwable -> assertReason(throwable, KnowledgeSourceException.Reason.NOT_ACCESSIBLE));

        when(fileCommonApi.getFileContent(any(FileReadReqDTO.class))).thenReturn(new byte[0]);
        assertThatThrownBy(() -> reader.read(501L))
                .satisfies(throwable -> assertReason(throwable, KnowledgeSourceException.Reason.NOT_ACCESSIBLE));
    }

    @Test
    void reasonCodesAreStableAndDoNotCarryContent() {
        KnowledgeSourceException failure =
                new KnowledgeSourceException(KnowledgeSourceException.Reason.READ_FAILED, "IOException");
        assertThat(failure.reasonCode()).isEqualTo("source-read_failed");
        assertThat(failure.getMessage()).isEqualTo("READ_FAILED:IOException");
        assertThat(KnowledgeSourceException.of(KnowledgeSourceException.Reason.NOT_ACCESSIBLE, null)
                        .getMessage())
                .isEqualTo("NOT_ACCESSIBLE");
    }
}
