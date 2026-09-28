package com.basicframework.module.ai.controller.app.v1.image;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiImageEditReqVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiImageGenerateReqVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaAssetRespVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaTaskRespVO;
import com.basicframework.module.ai.controller.app.v1.image.vo.AiMediaUsageRespVO;
import com.basicframework.module.ai.service.image.AiImageService;
import com.basicframework.module.ai.service.image.dto.AiImageEditDTO;
import com.basicframework.module.ai.service.image.dto.AiImageGenerateDTO;
import com.basicframework.module.ai.service.media.AiMediaTaskService;
import com.basicframework.module.ai.service.media.dto.AiMediaAssetDTO;
import com.basicframework.module.ai.service.media.dto.AiMediaTaskResultDTO;
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
 * AI 应用端图片生成与编辑（X03）。
 *
 * <p>受理与执行分离：本组端点只**受理**持久任务（幂等键去重）并返回任务事实，上游调用由常驻 Job 在
 * 后台按租约执行。因此协议里有明确的 queued/running/succeeded/failed/cancelled 与稳定失败码，
 * 没有"假进度"（上游不提供进度就不给百分比）。
 *
 * <p>所有端点只要求登录（{@code @AuthenticatedOnly}）：任务范围、底图归属（A07）、能力准入与产物核验
 * 全部由服务端判定；产物只以**私有文件编号**出现，协议里不存在任何上游地址。
 */
@Tag(name = "AI 应用端 - 图片生成与编辑")
@RestController
@RequestMapping("/ai/image")
@Validated
@RequiredArgsConstructor
public class AiImageController {

    private final AiImageService imageService;

    private final AiMediaTaskService taskService;

    @PostMapping("/generate")
    @Operation(summary = "受理图片生成任务（幂等键去重；不直曝上游地址）")
    @AuthenticatedOnly
    public CommonResult<AiMediaTaskRespVO> generate(@Valid @RequestBody AiImageGenerateReqVO reqVO) {
        return success(toRespVO(imageService.generate(new AiImageGenerateDTO()
                .setRequestKey(reqVO.getRequestKey())
                .setEndpointId(reqVO.getEndpointId())
                .setPrompt(reqVO.getPrompt())
                .setSize(reqVO.getSize())
                .setCount(reqVO.getCount())
                .setOutputFormat(reqVO.getOutputFormat()))));
    }

    @PostMapping("/edit")
    @Operation(summary = "受理图片编辑任务（底图必须是有权读取的私有图片）")
    @AuthenticatedOnly
    public CommonResult<AiMediaTaskRespVO> edit(@Valid @RequestBody AiImageEditReqVO reqVO) {
        return success(toRespVO(imageService.edit(new AiImageEditDTO()
                .setRequestKey(reqVO.getRequestKey())
                .setEndpointId(reqVO.getEndpointId())
                .setInstruction(reqVO.getInstruction())
                .setSourceFileId(reqVO.getImage().getFileId())
                .setSourceMime(reqVO.getImage().getMime())
                .setSourceSizeBytes(reqVO.getImage().getSize())
                .setSourceSha256(reqVO.getImage().getSha256())
                .setSize(reqVO.getSize())
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
                page.getList().stream().map(AiImageController::toRespVO).toList(), page.getTotal()));
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
