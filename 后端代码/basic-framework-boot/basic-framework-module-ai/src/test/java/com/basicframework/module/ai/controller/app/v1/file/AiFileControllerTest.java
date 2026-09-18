package com.basicframework.module.ai.controller.app.v1.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.file.vo.AiFileUploadRespVO;
import com.basicframework.module.ai.service.file.AiFileService;
import com.basicframework.module.ai.service.file.dto.AiFileUploadResultDTO;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** A07 应用端文件接口契约：三个端点都要求已认证主体（AI 票据），并原样委派业务类型与业务对象。 */
class AiFileControllerTest {

    private final AiFileService fileService = mock(AiFileService.class);

    private final AiFileController controller = new AiFileController(fileService);

    @Test
    void uploadDelegatesBusinessBindingAndReturnsFileId() throws Exception {
        when(fileService.upload(eq("ai_chat_session"), eq("session-1"), any(), any(), any()))
                .thenReturn(new AiFileUploadResultDTO()
                        .setFileId(88L)
                        .setBusinessType("ai_chat_session")
                        .setBusinessKey("session-1")
                        .setName("a.txt")
                        .setSize(2L));
        MultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", new byte[] {1, 2});

        AiFileUploadRespVO respVO =
                controller.upload("ai_chat_session", "session-1", file).getData();

        assertThat(respVO.getFileId()).isEqualTo(88L);
        assertThat(respVO.getBusinessKey()).isEqualTo("session-1");
        assertThat(respVO.getSize()).isEqualTo(2L);
    }

    @Test
    void readAndReleaseDelegateToService() {
        when(fileService.read(88L)).thenReturn(new byte[] {1, 2, 3});

        assertThat(controller.read(88L).getBody()).containsExactly(1, 2, 3);
        controller.release(88L);
        verify(fileService).read(88L);
        verify(fileService).release(88L);
    }

    @Test
    void everyEndpointRequiresAuthenticatedSubject() throws Exception {
        for (String methodName : new String[] {"upload", "read", "release"}) {
            Method method = findMethod(methodName);
            assertThat(method.getAnnotation(AuthenticatedOnly.class))
                    .as("%s 必须要求已认证主体（不接受匿名访问）", methodName)
                    .isNotNull();
        }
    }

    private static Method findMethod(String name) {
        for (Method method : AiFileController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("未找到方法：" + name);
    }
}
