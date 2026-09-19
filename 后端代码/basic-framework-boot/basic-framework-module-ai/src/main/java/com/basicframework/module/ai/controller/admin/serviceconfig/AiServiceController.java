package com.basicframework.module.ai.controller.admin.serviceconfig;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceCapabilityRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServicePageReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceResourceRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceResourceSaveReqVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceRespVO;
import com.basicframework.module.ai.controller.admin.serviceconfig.vo.AiServiceSaveReqVO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceDO;
import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.service.serviceconfig.AiServiceService;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceCapabilityDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceResourceSaveDTO;
import com.basicframework.module.ai.service.serviceconfig.dto.AiServiceSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 服务草稿管理接口（S01）。
 *
 * <p>草稿编辑使用乐观锁版本；资源绑定与"标记可发布"分别使用独立权限码
 * （{@code ai:service:bind} / {@code ai:service:publish}），与 V56 迁移的菜单种子一一对应。
 * 越权绑定与能力不足的判定都在服务层，Controller 只做协议转换。
 */
@Tag(name = "管理后台 - AI 服务")
@RestController
@RequestMapping("/ai/service")
@Validated
@RequiredArgsConstructor
public class AiServiceController {

    private final AiServiceService serviceService;

    @PostMapping("/create")
    @Operation(summary = "创建服务草稿")
    @PreAuthorize("@ss.hasPermission('ai:service:create')")
    public CommonResult<Long> createService(@Valid @RequestBody AiServiceSaveReqVO createReqVO) {
        return success(serviceService.createDraft(toSaveDTO(createReqVO)));
    }

    @PutMapping("/update")
    @Operation(summary = "修改服务草稿（乐观锁；配置变更递增修订号）")
    @PreAuthorize("@ss.hasPermission('ai:service:update')")
    public CommonResult<Boolean> updateService(@Valid @RequestBody AiServiceSaveReqVO updateReqVO) {
        serviceService.updateDraft(toSaveDTO(updateReqVO));
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除服务草稿（存在资源绑定或已发布时拒绝）")
    @PreAuthorize("@ss.hasPermission('ai:service:delete')")
    public CommonResult<Boolean> deleteService(
            @RequestParam("id") @NotNull @Positive Long id,
            @RequestParam("version") @NotNull @Positive Integer version) {
        serviceService.deleteDraft(id, version);
        return success(true);
    }

    @PutMapping("/mark-ready")
    @Operation(summary = "标记服务为可发布（能力与资源都必须满足）")
    @PreAuthorize("@ss.hasPermission('ai:service:publish')")
    public CommonResult<Boolean> markReady(
            @RequestParam("id") @NotNull @Positive Long id,
            @RequestParam("version") @NotNull @Positive Integer version) {
        serviceService.markReady(id, version);
        return success(true);
    }

    @GetMapping("/check-capabilities")
    @Operation(summary = "校验服务能力是否满足发布条件")
    @Parameter(name = "id", description = "服务编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<AiServiceCapabilityRespVO> checkCapabilities(@RequestParam("id") @NotNull @Positive Long id) {
        AiServiceCapabilityDTO capability = serviceService.checkCapabilities(id);
        return success(new AiServiceCapabilityRespVO()
                .setRequired(capability.getRequired())
                .setPublishable(capability.getPublishable())
                .setMissing(capability.getMissing())
                .setSatisfied(capability.isSatisfied()));
    }

    @PostMapping("/bind-resource")
    @Operation(summary = "绑定资源（越权或类型非法都拒绝）")
    @PreAuthorize("@ss.hasPermission('ai:service:bind')")
    public CommonResult<Long> bindResource(@Valid @RequestBody AiServiceResourceSaveReqVO reqVO) {
        return success(serviceService.bindResource(toResourceDTO(reqVO)));
    }

    @PutMapping("/unbind-resource")
    @Operation(summary = "解绑资源（乐观锁）")
    @PreAuthorize("@ss.hasPermission('ai:service:bind')")
    public CommonResult<Boolean> unbindResource(
            @RequestParam("bindingId") @NotNull @Positive Long bindingId,
            @RequestParam("version") @NotNull @Positive Integer version) {
        serviceService.unbindResource(bindingId, version);
        return success(true);
    }

    @GetMapping("/resources")
    @Operation(summary = "查询服务的草稿资源绑定")
    @Parameter(name = "id", description = "服务编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<List<AiServiceResourceRespVO>> listResources(@RequestParam("id") @NotNull @Positive Long id) {
        return success(serviceService.listDraftBindings(id).stream()
                .map(AiServiceController::toResourceRespVO)
                .collect(Collectors.toList()));
    }

    @GetMapping("/get")
    @Operation(summary = "查询服务详情")
    @Parameter(name = "id", description = "服务编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<AiServiceRespVO> getService(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(serviceService.getService(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "查询服务分页")
    @PreAuthorize("@ss.hasPermission('ai:service:query')")
    public CommonResult<PageResult<AiServiceRespVO>> getServicePage(@Valid AiServicePageReqVO pageReqVO) {
        PageResult<AiServiceDO> page = serviceService.getServicePage(
                pageReqVO, pageReqVO.getAppId(), pageReqVO.getCode(), pageReqVO.getStatus());
        List<AiServiceRespVO> list =
                page.getList().stream().map(AiServiceController::toRespVO).collect(Collectors.toList());
        return success(new PageResult<>(list, page.getTotal()));
    }

    private static AiServiceSaveDTO toSaveDTO(AiServiceSaveReqVO reqVO) {
        return new AiServiceSaveDTO()
                .setId(reqVO.getId())
                .setAppId(reqVO.getAppId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setModelEndpointId(reqVO.getModelEndpointId())
                .setPromptTemplate(reqVO.getPromptTemplate())
                .setInputSchema(reqVO.getInputSchema())
                .setOutputSchema(reqVO.getOutputSchema())
                .setRequiredCapabilities(reqVO.getRequiredCapabilities())
                .setRunSubjectType(reqVO.getRunSubjectType())
                .setEvalThreshold(reqVO.getEvalThreshold())
                .setVersion(reqVO.getVersion());
    }

    private static AiServiceResourceSaveDTO toResourceDTO(AiServiceResourceSaveReqVO reqVO) {
        return new AiServiceResourceSaveDTO()
                .setServiceId(reqVO.getServiceId())
                .setResourceType(reqVO.getResourceType())
                .setResourceKey(reqVO.getResourceKey())
                .setActions(reqVO.getActions());
    }

    private static AiServiceRespVO toRespVO(AiServiceDO service) {
        return new AiServiceRespVO()
                .setId(service.getId())
                .setAppId(service.getAppId())
                .setCode(service.getCode())
                .setName(service.getName())
                .setDescription(service.getDescription())
                .setStatus(service.getStatus())
                .setModelEndpointId(service.getModelEndpointId())
                .setPromptTemplate(service.getPromptTemplate())
                .setInputSchema(service.getInputSchema())
                .setOutputSchema(service.getOutputSchema())
                .setRequiredCapabilities(
                        service.getRequiredCapabilities() == null
                                ? List.of()
                                : Arrays.stream(service.getRequiredCapabilities()
                                                .split(","))
                                        .map(String::trim)
                                        .filter(value -> !value.isEmpty())
                                        .collect(Collectors.toList()))
                .setRunSubjectType(service.getRunSubjectType())
                .setEvalThreshold(service.getEvalThreshold())
                .setDraftRevision(service.getDraftRevision())
                .setVersion(service.getVersion())
                .setCreateTime(service.getCreateTime());
    }

    private static AiServiceResourceRespVO toResourceRespVO(AiServiceResourceDO binding) {
        return new AiServiceResourceRespVO()
                .setId(binding.getId())
                .setServiceId(binding.getServiceId())
                .setResourceType(binding.getResourceType())
                .setResourceKey(binding.getResourceKey())
                .setActions(
                        binding.getActions() == null
                                ? List.of()
                                : Arrays.stream(binding.getActions().split(","))
                                        .map(String::trim)
                                        .filter(value -> !value.isEmpty())
                                        .collect(Collectors.toCollection(LinkedHashSet::new))
                                        .stream()
                                        .collect(Collectors.toList()))
                .setStatus(binding.getStatus())
                .setVersion(binding.getVersion())
                .setCreateTime(binding.getCreateTime());
    }
}
