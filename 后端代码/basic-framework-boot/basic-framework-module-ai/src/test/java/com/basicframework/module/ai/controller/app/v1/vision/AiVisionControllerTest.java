package com.basicframework.module.ai.controller.app.v1.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.vision.vo.AiDocumentOcrReqVO;
import com.basicframework.module.ai.controller.app.v1.vision.vo.AiDocumentOcrRespVO;
import com.basicframework.module.ai.controller.app.v1.vision.vo.AiVisionImageReqVO;
import com.basicframework.module.ai.controller.app.v1.vision.vo.AiVisionOcrReqVO;
import com.basicframework.module.ai.controller.app.v1.vision.vo.AiVisionOcrRespVO;
import com.basicframework.module.ai.controller.app.v1.vision.vo.AiVisionTextRespVO;
import com.basicframework.module.ai.controller.app.v1.vision.vo.AiVisionUnderstandReqVO;
import com.basicframework.module.ai.service.document.AiDocumentOcrService;
import com.basicframework.module.ai.service.document.dto.AiDocumentOcrRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexResultDTO;
import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;
import com.basicframework.module.ai.service.vision.AiVisionRegionSource;
import com.basicframework.module.ai.service.vision.AiVisionService;
import com.basicframework.module.ai.service.vision.dto.AiVisionImageRefDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionRegionDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionTextResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionUnderstandRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionUsageDTO;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 应用端图片理解与 OCR 接口契约（X02）：三个端点都要求已认证主体，
 * 请求字段原样委派（图片引用只含私有文件标识），响应只暴露文本/页码/范围/置信度来源与私有文件编号。
 */
class AiVisionControllerTest {

    private final AiVisionService visionService = mock(AiVisionService.class);

    private final AiDocumentOcrService documentOcrService = mock(AiDocumentOcrService.class);

    private final AiVisionController controller = new AiVisionController(visionService, documentOcrService);

    @Test
    void understandDelegatesVerifiedImageReferenceAndMapsUsage() {
        when(visionService.understandImage(any()))
                .thenReturn(new AiVisionTextResultDTO()
                        .setFileId(88L)
                        .setText("图片描述")
                        .setUsage(new AiVisionUsageDTO("REPORTED", 33, "TOKEN"))
                        .setFinishReason("stop"));

        AiVisionTextRespVO respVO = controller
                .understandImage(new AiVisionUnderstandReqVO()
                        .setEndpointId(7L)
                        .setImage(imageReqVO(88L))
                        .setInstruction("描述图片")
                        .setTimeoutMillis(30_000L))
                .getData();

        ArgumentCaptor<AiVisionUnderstandRequestDTO> captor =
                ArgumentCaptor.forClass(AiVisionUnderstandRequestDTO.class);
        verify(visionService).understandImage(captor.capture());
        assertThat(captor.getValue().getEndpointId()).isEqualTo(7L);
        assertThat(captor.getValue().getImage().getFileId()).isEqualTo(88L);
        assertThat(captor.getValue().getImage().getMime()).isEqualTo("image/png");
        assertThat(captor.getValue().getInstruction()).isEqualTo("描述图片");
        assertThat(respVO.getFileId()).isEqualTo(88L);
        assertThat(respVO.getText()).isEqualTo("图片描述");
        assertThat(respVO.getUsage().getSource()).isEqualTo("REPORTED");
        assertThat(respVO.getUsage().getQuantity()).isEqualTo(33);
        assertThat(respVO.getFinishReason()).isEqualTo("stop");
    }

    @Test
    void ocrMapsProvenanceWithoutFabricatingConfidence() {
        when(visionService.recognizeText(any()))
                .thenReturn(new AiVisionOcrResultDTO()
                        .setFileId(88L)
                        .setPage(1)
                        .setText("识别文本")
                        .setRegion(AiVisionRegionDTO.wholePage())
                        .setRegionSource(AiVisionRegionSource.WHOLE_PAGE)
                        .setConfidenceSource(AiVisionConfidenceSource.UNKNOWN)
                        .setConfidence(null)
                        .setReviewRequired(true)
                        .setUsage(AiVisionUsageDTO.unknown()));

        AiVisionOcrRespVO respVO = controller
                .recognizeText(new AiVisionOcrReqVO()
                        .setEndpointId(7L)
                        .setImage(imageReqVO(88L))
                        .setLanguageHint("zh-CN"))
                .getData();

        ArgumentCaptor<AiVisionOcrRequestDTO> captor = ArgumentCaptor.forClass(AiVisionOcrRequestDTO.class);
        verify(visionService).recognizeText(captor.capture());
        assertThat(captor.getValue().getLanguageHint()).isEqualTo("zh-CN");
        assertThat(respVO.getPage()).isEqualTo(1);
        assertThat(respVO.getRegion().getWidth()).isEqualTo(1d);
        assertThat(respVO.getRegionSource()).isEqualTo("WHOLE_PAGE");
        assertThat(respVO.getConfidenceSource()).isEqualTo("UNKNOWN");
        assertThat(respVO.getConfidence()).isNull();
        assertThat(respVO.isReviewRequired()).isTrue();
        assertThat(respVO.getUsage().getSource()).isEqualTo("UNKNOWN");
        assertThat(respVO.getUsage().getQuantity()).isNull();
    }

    @Test
    void documentOcrDelegatesPageImagesInOrder() {
        when(documentOcrService.recognizeAndReindex(any()))
                .thenReturn(new AiOcrDocumentReindexResultDTO()
                        .setDocumentId(5L)
                        .setVersionId(77L)
                        .setVersionNo(2)
                        .setTaskId(88L)
                        .setCreatedVersion(true)
                        .setPageCount(2)
                        .setConfidenceSource(AiVisionConfidenceSource.UNKNOWN)
                        .setReviewRequired(true)
                        .setSourceFileId(101L)
                        .setDerivedFileId(2048L)
                        .setSourceRef("ocr:sourceFileId=101"));

        AiDocumentOcrRespVO respVO = controller
                .recognizeDocument(new AiDocumentOcrReqVO()
                        .setDocumentId(5L)
                        .setEndpointId(7L)
                        .setPageImages(List.of(imageReqVO(101L), imageReqVO(102L))))
                .getData();

        ArgumentCaptor<AiDocumentOcrRequestDTO> captor = ArgumentCaptor.forClass(AiDocumentOcrRequestDTO.class);
        verify(documentOcrService).recognizeAndReindex(captor.capture());
        assertThat(captor.getValue().getPageImages())
                .extracting(AiVisionImageRefDTO::getFileId)
                .containsExactly(101L, 102L);
        assertThat(respVO.getVersionId()).isEqualTo(77L);
        assertThat(respVO.getTaskId()).isEqualTo(88L);
        assertThat(respVO.getConfidenceSource()).isEqualTo("UNKNOWN");
        assertThat(respVO.isReviewRequired()).isTrue();
        assertThat(respVO.getDerivedFileId()).isEqualTo(2048L);
    }

    @Test
    void everyEndpointRequiresAuthenticatedSubject() {
        for (String methodName : new String[] {"understandImage", "recognizeText", "recognizeDocument"}) {
            Method method = findMethod(methodName);
            assertThat(method.getAnnotation(AuthenticatedOnly.class))
                    .as("%s 必须要求已认证主体（不接受匿名访问）", methodName)
                    .isNotNull();
        }
    }

    private static AiVisionImageReqVO imageReqVO(Long fileId) {
        return new AiVisionImageReqVO().setFileId(fileId).setMime("image/png").setSize(2048L);
    }

    private static Method findMethod(String name) {
        for (Method method : AiVisionController.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("未找到方法：" + name);
    }
}
