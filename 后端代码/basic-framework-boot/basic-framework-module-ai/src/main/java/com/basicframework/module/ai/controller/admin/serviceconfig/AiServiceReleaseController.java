package com.basicframework.module.ai.controller.admin.serviceconfig;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceEvaluationRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceEvaluationSaveReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceReleaseActionReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceReleaseCreateReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceReleaseRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceResourceRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceRunResourceRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceRunSnapshotRespVO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceReleaseEvaluationDO;
import com.basicframework.module.ai.domain.runtime.AiRunSnapshot;
import com.basicframework.module.ai.service.serviceconfig.AiServiceReleaseService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceEvaluationSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceRunSnapshotDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 服务发布与评测接口（S02 发布，S03 回退与运行解析）。
 *
 * <p>权限码与 V57 迁移的菜单种子一一对应：创建候选 {@code ai:service:release}、
 * 切换发布/停用/回退 {@code ai:service:activate}、记录评测 {@code ai:service:evaluate}；
 * 查询类接口复用 {@code ai:service:query}。发布版本内容只读，接口不提供任何修改入口。
 */
@Tag(name = "管理后台 - AI 服务发布")
@RestController
@RequestMapping("/ai/service/release")
@Validated
@RequiredArgsConstructor
public class AiServiceReleaseController {

    private final AiServiceReleaseService releaseService;

    @PostMapping("/create-candidate")
    @Operation(summary = "创建发布候选（冻结内容、端点配置版本与资源绑定，并锁定评测门槛）")
    @PreAuthorize("@ss.hasPermission('ai:service:release')")
    public CommonResult<Long> createCandidate(@Valid @RequestBody AiServiceReleaseCreateReqVO reqVO) {
        return success(releaseService.createCandidate(reqVO.getServiceId(), reqVO.getVersion()));
    }

    @PostMapping("/evaluate")
    @Operation(summary = "记录评测结论（绑定内容摘要与端点配置版本；通过与否由平台判定）")
    @PreAuthorize("@ss.hasPermission('ai:service:evaluate')")
    public CommonResult<Long> evaluate(@Valid @RequestBody AiServiceEvaluationSaveReqVO reqVO) {
        return success(releaseService.recordEvaluation(new AiServiceEvaluationSaveDTO()
                .setReleaseId(reqVO.getReleaseId())
                .setScore(reqVO.getScore())
                .setCaseCount(reqVO.getCaseCount())
                .setNotes(reqVO.getNotes())));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布（预检查通过后切换别名；失败不改变当前生效版本）")
    @PreAuthorize("@ss.hasPermission('ai:service:activate')")
    public CommonResult<Boolean> publish(@Valid @RequestBody AiServiceReleaseActionReqVO reqVO) {
        releaseService.publish(reqVO.getReleaseId(), reqVO.getVersion());
        return success(true);
    }

    @PostMapping("/disable")
    @Operation(summary = "停用（退役当前生效版本，新运行不再解析到该服务）")
    @PreAuthorize("@ss.hasPermission('ai:service:activate')")
    public CommonResult<Boolean> disable(@Valid @RequestBody AiServiceReleaseActionReqVO reqVO) {
        releaseService.disable(reqVO.getServiceId(), reqVO.getVersion());
        return success(true);
    }

    @PostMapping("/rollback")
    @Operation(summary = "回退（把别名切回历史版本；只影响后续运行，已固定版本的会话不受影响）")
    @PreAuthorize("@ss.hasPermission('ai:service:activate')")
    public CommonResult<Boolean> rollback(@Valid @RequestBody AiServiceReleaseActionReqVO reqVO) {
        releaseService.rollback(reqVO.getReleaseId(), reqVO.getVersion());
        return success(true);
    }

    @GetMapping("/resolve")
    @Operation(summary = "运行解析（新运行按别名解析到生效版本，返回本次运行的版本固定值）")
    @Parameter(name = "serviceId", description = "服务编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<AiServiceRunSnapshotRespVO> resolve(
            @RequestParam("serviceId") @NotNull @Positive Long serviceId) {
        return success(toRunSnapshotRespVO(releaseService.resolveForNewRun(serviceId)));
    }

    @GetMapping("/check-publish")
    @Operation(summary = "发布预检查（返回未满足项，空表示可发布）")
    @Parameter(name = "releaseId", description = "发布版本编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<List<String>> checkPublish(@RequestParam("releaseId") @NotNull @Positive Long releaseId) {
        return success(releaseService.checkPublishReadiness(releaseId));
    }

    @GetMapping("/list")
    @Operation(summary = "查询服务的发布版本")
    @Parameter(name = "serviceId", description = "服务编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<List<AiServiceReleaseRespVO>> listReleases(
            @RequestParam("serviceId") @NotNull @Positive Long serviceId) {
        return success(releaseService.listReleases(serviceId).stream()
                .map(AiServiceReleaseController::toReleaseRespVO)
                .collect(Collectors.toList()));
    }

    @GetMapping("/bindings")
    @Operation(summary = "查询发布版本冻结的资源绑定快照")
    @Parameter(name = "releaseId", description = "发布版本编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<List<AiServiceResourceRespVO>> listBindings(
            @RequestParam("releaseId") @NotNull @Positive Long releaseId) {
        return success(releaseService.listReleaseBindings(releaseId).stream()
                .map(binding -> new AiServiceResourceRespVO()
                        .setId(binding.getId())
                        .setServiceId(binding.getServiceId())
                        .setReleaseId(binding.getReleaseId())
                        .setResourceType(binding.getResourceType())
                        .setResourceKey(binding.getResourceKey())
                        .setActions(Arrays.stream(binding.getActions().split(","))
                                .map(String::trim)
                                .filter(value -> !value.isEmpty())
                                .collect(Collectors.toList()))
                        .setStatus(binding.getStatus())
                        .setVersion(binding.getVersion())
                        .setCreateTime(binding.getCreateTime()))
                .collect(Collectors.toList()));
    }

    @GetMapping("/evaluations")
    @Operation(summary = "查询发布版本的评测记录（最新在前）")
    @Parameter(name = "releaseId", description = "发布版本编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<List<AiServiceEvaluationRespVO>> listEvaluations(
            @RequestParam("releaseId") @NotNull @Positive Long releaseId) {
        return success(releaseService.listEvaluations(releaseId).stream()
                .map(AiServiceReleaseController::toEvaluationRespVO)
                .collect(Collectors.toList()));
    }

    private static AiServiceReleaseRespVO toReleaseRespVO(AiServiceReleaseDO release) {
        return new AiServiceReleaseRespVO()
                .setId(release.getId())
                .setServiceId(release.getServiceId())
                .setReleaseVersion(release.getReleaseVersion())
                .setModelEndpointId(release.getModelEndpointId())
                .setEndpointConfigRevision(release.getEndpointConfigRevision())
                .setRequiredCapabilities(release.getRequiredCapabilities())
                .setEvalThreshold(release.getEvalThreshold())
                .setContentHash(release.getContentHash())
                .setStatus(release.getStatus())
                .setVersion(release.getVersion())
                .setCreateTime(release.getCreateTime());
    }

    /** 运行解析结果：只暴露版本固定值，不含提示词正文、授权结论与凭据。 */
    private static AiServiceRunSnapshotRespVO toRunSnapshotRespVO(AiServiceRunSnapshotDTO snapshot) {
        AiRunSnapshot pin = snapshot.getPin();
        return new AiServiceRunSnapshotRespVO()
                .setServiceId(pin.getServiceId())
                .setReleaseId(pin.getReleaseId())
                .setReleaseVersion(pin.getReleaseVersion())
                .setStatus(snapshot.getRelease().getStatus())
                .setContentHash(pin.getContentHash())
                .setModelEndpointId(pin.getModelEndpointId())
                .setModelRevision(pin.getModelRevision())
                .setPinned(snapshot.isPinned())
                .setResources(pin.getResources().stream()
                        .map(resource -> new AiServiceRunResourceRespVO()
                                .setId(resource.getId())
                                .setResourceType(resource.getResourceType())
                                .setResourceKey(resource.getResourceKey())
                                .setVersion(resource.getVersion()))
                        .collect(Collectors.toList()));
    }

    private static AiServiceEvaluationRespVO toEvaluationRespVO(AiServiceReleaseEvaluationDO evaluation) {
        return new AiServiceEvaluationRespVO()
                .setId(evaluation.getId())
                .setReleaseId(evaluation.getReleaseId())
                .setContentHash(evaluation.getContentHash())
                .setEndpointConfigRevision(evaluation.getEndpointConfigRevision())
                .setScore(evaluation.getScore())
                .setThreshold(evaluation.getThreshold())
                .setPassed(evaluation.getPassed())
                .setCaseCount(evaluation.getCaseCount())
                .setNotes(evaluation.getNotes())
                .setCreateTime(evaluation.getCreateTime());
    }
}
