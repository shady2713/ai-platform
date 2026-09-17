package com.basicframework.module.ai.controller.admin.model;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelCapabilityOverviewRespVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelProbeResultRespVO;
import com.basicframework.module.ai.service.model.AiModelCapabilityProbeService;
import com.basicframework.module.ai.service.model.dto.AiModelCapabilityOverviewDTO;
import com.basicframework.module.ai.service.model.dto.AiModelProbeResultDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型能力探测接口（M04）。
 *
 * <p>探测是对上游的**真实调用**，因此放在显式触发的 POST 入口；查询走只读接口。
 * 权限码 {@code ai:model-endpoint:probe} 由 V49 迁移登记；停用端点同样可探测，
 * 探测失败会以 FAILED 结论返回，不会被启用状态掩盖。
 *
 * <p>响应永不含凭据与上游报文：失败只返回 {@code ModelException.Reason} 名称。
 */
@Tag(name = "管理后台 - AI 模型能力探测")
@RestController
@RequestMapping("/ai/model-endpoint")
@Validated
@RequiredArgsConstructor
public class AiModelCapabilityProbeController {

    private final AiModelCapabilityProbeService probeService;

    @PostMapping("/{id}/probe")
    @Operation(summary = "对端点执行能力探测（真实调用上游）")
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:probe')")
    public CommonResult<List<AiModelProbeResultRespVO>> probeEndpoint(
            @Parameter(description = "端点编号", required = true) @PathVariable("id") Long id) {
        return success(toRespVOList(probeService.probeAll(id)));
    }

    @GetMapping("/{id}/probe")
    @Operation(summary = "查询端点最近一次探测结论（每种类型一条）")
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:query')")
    public CommonResult<List<AiModelProbeResultRespVO>> getLatestProbeResults(
            @Parameter(description = "端点编号", required = true) @PathVariable("id") Long id) {
        return success(toRespVOList(probeService.getLatestResults(id)));
    }

    @GetMapping("/{id}/capabilities")
    @Operation(summary = "查询端点能力总览（声明、确认与可发布范围）")
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:query')")
    public CommonResult<AiModelCapabilityOverviewRespVO> getCapabilityOverview(
            @Parameter(description = "端点编号", required = true) @PathVariable("id") Long id) {
        AiModelCapabilityOverviewDTO overview = probeService.getCapabilityOverview(id);
        return success(new AiModelCapabilityOverviewRespVO()
                .setEndpointId(overview.getEndpointId())
                .setDeclared(overview.getDeclared())
                .setSupported(overview.getSupported())
                .setPublishable(overview.getPublishable()));
    }

    private static List<AiModelProbeResultRespVO> toRespVOList(List<AiModelProbeResultDTO> results) {
        return results.stream()
                .map(result -> new AiModelProbeResultRespVO()
                        .setProbeKind(result.getProbeKind())
                        .setStatus(result.getStatus())
                        .setDetailCode(result.getDetailCode())
                        .setEmbeddingDimension(result.getEmbeddingDimension())
                        .setLatencyMs(result.getLatencyMs())
                        .setConfigRevision(result.getConfigRevision()))
                .collect(java.util.stream.Collectors.toList());
    }
}
