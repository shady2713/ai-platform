package com.basicframework.module.ai.controller.app.v1.speech;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaAssetRespVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaTaskRespVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaUsageRespVO;
import com.basicframework.module.ai.controller.app.v1.speech.vo.AiSpeechSynthesizeReqVO;
import com.basicframework.module.ai.controller.app.v1.speech.vo.AiSpeechTranscribeReqVO;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaAssetDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
import com.basicframework.module.ai.service.speech.AiSpeechService;
import com.basicframework.module.ai.service.speech.dto.AiSpeechSynthesizeDTO;
import com.basicframework.module.ai.service.speech.dto.AiSpeechTranscribeDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 应用端非实时语音（X04）：STT 与 TTS 的受理与任务查询，复用 X03 的媒体任务语义。
 *
 * <p>受理与执行分离：本组端点只**受理**持久任务（幂等键去重）并返回任务事实，上游调用由常驻
 * {@code aiMediaTaskJob} 在后台按租约执行。因此协议里有明确的 queued/running/succeeded/failed/cancelled
 * 与稳定失败码，没有"假进度"。
 *
 * <p>所有端点只要求登录（{@code @AuthenticatedOnly}）：任务范围、音频归属（A07）、能力准入与结果核验
 * 全部由服务端判定；音频输入与产物只以**私有文件编号**出现，协议里不存在任何上游地址。
 *
 * <p>响应 VO 与图片端点共用（`controller.app.v1.image.vo` 的 {@code AiMedia*RespVO} 是通用媒体任务协议）：
 * X04 不复制第二套任务/产物/用量的协议形状，两个能力域的任务事实完全一致。
 */
@Tag(name = "AI 应用端 - 非实时语音")
@RestController
@RequestMapping("/ai/speech")
@Validated
@RequiredArgsConstructor
public class AiSpeechController {

    private final AiSpeechService speechService;

    private final AiMediaTaskService taskService;

    @PostMapping("/transcribe")
    @Operation(summary = "受理语音转写任务（音频必须是有权读取的私有音频；幂等键去重）")
    @AuthenticatedOnly
    public CommonResult<AiMediaTaskRespVO> transcribe(@Valid @RequestBody AiSpeechTranscribeReqVO reqVO) {
        return success(toRespVO(speechService.transcribe(new AiSpeechTranscribeDTO()
                .setRequestKey(reqVO.getRequestKey())
                .setEndpointId(reqVO.getEndpointId())
                .setSourceFileId(reqVO.getAudio().getFileId())
                .setSourceMime(reqVO.getAudio().getMime())
                .setSourceSizeBytes(reqVO.getAudio().getSize())
                .setSourceSha256(reqVO.getAudio().getSha256())
                .setSourceDurationMillis(reqVO.getAudio().getDurationMs())
                .setLanguageHint(reqVO.getLanguageHint()))));
    }

    @PostMapping("/synthesize")
    @Operation(summary = "受理语音合成任务（文本 + 受控音色/格式；产物是私有音频文件）")
    @AuthenticatedOnly
    public CommonResult<AiMediaTaskRespVO> synthesize(@Valid @RequestBody AiSpeechSynthesizeReqVO reqVO) {
        return success(toRespVO(speechService.synthesize(new AiSpeechSynthesizeDTO()
                .setRequestKey(reqVO.getRequestKey())
                .setEndpointId(reqVO.getEndpointId())
                .setText(reqVO.getText())
                .setVoice(reqVO.getVoice())
                .setOutputFormat(reqVO.getOutputFormat()))));
    }

    @GetMapping("/task")
    @Operation(summary = "查询任务（含产物与用量；越权与不存在同语义）")
    @AuthenticatedOnly
    public CommonResult<AiMediaTaskRespVO> getTask(@RequestParam("id") Long id) {
        return success(toRespVO(taskService.getTask(id)));
    }

    @GetMapping("/task/page")
    @Operation(summary = "分页查询任务（按当前主体范围）")
    @AuthenticatedOnly
    public CommonResult<PageResult<AiMediaTaskRespVO>> getTaskPage(
            @Valid com.basicframework.framework.common.pojo.PageParam pageParam,
            @RequestParam(value = "mediaKind", required = false) String mediaKind,
            @RequestParam(value = "status", required = false) String status) {
        PageResult<AiMediaTaskResultDTO> page = taskService.getTaskPage(pageParam, mediaKind, status);
        return success(new PageResult<>(
                page.getList().stream().map(AiSpeechController::toRespVO).toList(), page.getTotal()));
    }

    @PostMapping("/task/cancel")
    @Operation(summary = "取消任务（仅待领取状态可取消；执行中不可中断）")
    @AuthenticatedOnly
    public CommonResult<AiMediaTaskRespVO> cancel(@RequestParam("id") Long id) {
        return success(toRespVO(taskService.cancel(id)));
    }

    /** 服务层 DTO → 协议层 VO：只暴露平台事实（状态/产物/用量），不做任何字段改名式改写。 */
    private static AiMediaTaskRespVO toRespVO(AiMediaTaskResultDTO dto) {
        return new AiMediaTaskRespVO()
                .setId(dto.getId())
                .setMediaKind(dto.getMediaKind())
                .setOperation(dto.getOperation())
                .setCapability(dto.getCapability())
                .setStatus(dto.getStatus())
                .setFailureCode(dto.getFailureCode())
                .setInputText(dto.getInputText())
                .setSourceFileId(dto.getSourceFileId())
                .setEndpointId(dto.getEndpointId())
                .setOutputCount(dto.getOutputCount())
                .setOutputFormat(dto.getOutputFormat())
                .setVoice(dto.getVoice())
                .setLanguageHint(dto.getLanguageHint())
                .setResultCount(dto.getResultCount())
                .setUsage(new AiMediaUsageRespVO()
                        .setUnit(dto.getUsageUnit())
                        .setQuantity(dto.getUsageQuantity())
                        .setSource(dto.getUsageSource()))
                .setCreateTime(dto.getCreateTime())
                .setAssets(toAssetVOs(dto.getAssets()));
    }

    private static List<AiMediaAssetRespVO> toAssetVOs(List<AiMediaAssetDTO> assets) {
        if (assets == null) {
            return List.of();
        }
        return assets.stream()
                .map(asset -> new AiMediaAssetRespVO()
                        .setFileId(asset.getFileId())
                        .setOrdinal(asset.getOrdinal())
                        .setMimeType(asset.getMimeType())
                        .setSizeBytes(asset.getSizeBytes())
                        .setSha256(asset.getSha256())
                        .setWidth(asset.getWidth())
                        .setHeight(asset.getHeight())
                        .setDurationMillis(asset.getDurationMillis()))
                .toList();
    }
}
