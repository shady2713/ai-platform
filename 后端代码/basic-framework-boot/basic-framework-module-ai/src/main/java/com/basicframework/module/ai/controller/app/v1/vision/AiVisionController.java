package com.basicframework.module.ai.controller.app.v1.vision;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
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
import com.basicframework.module.ai.service.vision.AiVisionService;
import com.basicframework.module.ai.service.vision.dto.AiVisionImageRefDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionOcrResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionTextResultDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionUnderstandRequestDTO;
import com.basicframework.module.ai.service.vision.dto.AiVisionUsageDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 应用端图片理解与 OCR（X02）。
 *
 * <p>三个端点都只要求登录（{@code @AuthenticatedOnly}）：归属、能力准入与内容校验全部由**服务端**判定——
 * 图片必须是调用主体有权读取的私有文件（A07，无权限与不存在同语义），端点能力必须已声明且探测确认
 * （未开通按 {@code AI_MODEL_CAPABILITY_NOT_ENABLED} 拒绝且零外发），图片声明必须与真实内容一致
 * （字节数/文件头/像素上限）。
 *
 * <p>协议里没有任何上游地址、供应商或模型标识字段：结果是文本与**私有文件标识**，
 * 读取仍走受控文件接口按当前授权判定。
 */
@Tag(name = "AI 应用端 - 图片理解与 OCR")
@RestController
@RequestMapping("/ai/vision")
@Validated
@RequiredArgsConstructor
public class AiVisionController {

    private final AiVisionService visionService;

    private final AiDocumentOcrService documentOcrService;

    @PostMapping("/image/understand")
    @Operation(summary = "图片理解（受控图片 + 显式端点；能力未开通则明确拒绝）")
    @AuthenticatedOnly
    public CommonResult<AiVisionTextRespVO> understandImage(@Valid @RequestBody AiVisionUnderstandReqVO reqVO) {
        AiVisionTextResultDTO result = visionService.understandImage(new AiVisionUnderstandRequestDTO()
                .setEndpointId(reqVO.getEndpointId())
                .setImage(toImageRef(reqVO.getImage()))
                .setInstruction(reqVO.getInstruction())
                .setTimeoutMillis(reqVO.getTimeoutMillis()));
        return success(new AiVisionTextRespVO()
                .setFileId(result.getFileId())
                .setText(result.getText())
                .setUsage(toUsage(result.getUsage()))
                .setFinishReason(result.getFinishReason()));
    }

    @PostMapping("/image/ocr")
    @Operation(summary = "单张图片 OCR（返回文本与页码/范围/置信度来源；不伪装识别质量）")
    @AuthenticatedOnly
    public CommonResult<AiVisionOcrRespVO> recognizeText(@Valid @RequestBody AiVisionOcrReqVO reqVO) {
        AiVisionOcrResultDTO result = visionService.recognizeText(new AiVisionOcrRequestDTO()
                .setEndpointId(reqVO.getEndpointId())
                .setImage(toImageRef(reqVO.getImage()))
                .setLanguageHint(reqVO.getLanguageHint())
                .setTimeoutMillis(reqVO.getTimeoutMillis()));
        return success(toOcrRespVO(result));
    }

    @PostMapping("/document/ocr")
    @Operation(summary = "扫描件逐页 OCR 并重索引为该文档的新版本（失败不替换旧版本）")
    @AuthenticatedOnly
    public CommonResult<AiDocumentOcrRespVO> recognizeDocument(@Valid @RequestBody AiDocumentOcrReqVO reqVO) {
        AiOcrDocumentReindexResultDTO result = documentOcrService.recognizeAndReindex(new AiDocumentOcrRequestDTO()
                .setDocumentId(reqVO.getDocumentId())
                .setEndpointId(reqVO.getEndpointId())
                .setPageImages(toImageRefs(reqVO.getPageImages()))
                .setLanguageHint(reqVO.getLanguageHint())
                .setTimeoutMillis(reqVO.getTimeoutMillis()));
        return success(new AiDocumentOcrRespVO()
                .setDocumentId(result.getDocumentId())
                .setVersionId(result.getVersionId())
                .setVersionNo(result.getVersionNo())
                .setTaskId(result.getTaskId())
                .setCreatedVersion(result.isCreatedVersion())
                .setReused(result.isReused())
                .setPageCount(result.getPageCount())
                .setCharacterCount(result.getCharacterCount())
                .setConfidenceSource(
                        result.getConfidenceSource() == null
                                ? null
                                : result.getConfidenceSource().name())
                .setReviewRequired(result.isReviewRequired())
                .setSourceFileId(result.getSourceFileId())
                .setDerivedFileId(result.getDerivedFileId())
                .setSourceRef(result.getSourceRef()));
    }

    private static AiVisionImageRefDTO toImageRef(AiVisionImageReqVO reqVO) {
        if (reqVO == null) {
            return null;
        }
        return new AiVisionImageRefDTO()
                .setFileId(reqVO.getFileId())
                .setMime(reqVO.getMime())
                .setSize(reqVO.getSize())
                .setSha256(reqVO.getSha256());
    }

    private static List<AiVisionImageRefDTO> toImageRefs(List<AiVisionImageReqVO> reqVOs) {
        return reqVOs == null
                ? null
                : reqVOs.stream().map(AiVisionController::toImageRef).toList();
    }

    private static AiVisionTextRespVO.Usage toUsage(AiVisionUsageDTO usage) {
        if (usage == null) {
            return null;
        }
        return new AiVisionTextRespVO.Usage()
                .setSource(usage.source())
                .setQuantity(usage.quantity())
                .setUnit(usage.unit());
    }

    private static AiVisionOcrRespVO toOcrRespVO(AiVisionOcrResultDTO result) {
        AiVisionOcrRespVO respVO = new AiVisionOcrRespVO()
                .setFileId(result.getFileId())
                .setPage(result.getPage())
                .setText(result.getText())
                .setRegionSource(
                        result.getRegionSource() == null
                                ? null
                                : result.getRegionSource().name())
                .setConfidenceSource(
                        result.getConfidenceSource() == null
                                ? null
                                : result.getConfidenceSource().name())
                .setConfidence(result.getConfidence())
                .setReviewRequired(result.isReviewRequired())
                .setUsage(toUsage(result.getUsage()));
        if (result.getRegion() != null) {
            respVO.setRegion(new AiVisionOcrRespVO.Region()
                    .setX(result.getRegion().getX())
                    .setY(result.getRegion().getY())
                    .setWidth(result.getRegion().getWidth())
                    .setHeight(result.getRegion().getHeight()));
        }
        return respVO;
    }
}
