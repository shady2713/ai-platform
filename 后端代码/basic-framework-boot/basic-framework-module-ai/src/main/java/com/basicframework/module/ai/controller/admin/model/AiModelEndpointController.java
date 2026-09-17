package com.basicframework.module.ai.controller.admin.model;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import cn.hutool.core.util.StrUtil;
import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointCredentialReqVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointPageReqVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointRespVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointRevisionRespVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointSaveReqVO;
import com.basicframework.module.ai.controller.admin.model.vo.AiModelEndpointStatusReqVO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointDO;
import com.basicframework.module.ai.dal.dataobject.model.AiModelEndpointRevisionDO;
import com.basicframework.module.ai.service.model.AiModelEndpointService;
import com.basicframework.module.ai.service.model.dto.AiModelEndpointSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Arrays;
import java.util.List;
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
 * AI 模型端点管理接口。
 *
 * <p>响应永不含凭据：只返回 {@code credentialConfigured} 标识；凭据写入与轮换走独立入口并只保存密文。
 * 权限码与 V48 迁移的 system_menu 种子一一对应。
 */
@Tag(name = "管理后台 - AI 模型端点")
@RestController
@RequestMapping("/ai/model-endpoint")
@Validated
@RequiredArgsConstructor
public class AiModelEndpointController {

    private final AiModelEndpointService endpointService;

    @PostMapping("/create")
    @Operation(summary = "创建模型端点")
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:create')")
    public CommonResult<Long> createEndpoint(@Valid @RequestBody AiModelEndpointSaveReqVO createReqVO) {
        return success(endpointService.createEndpoint(toSaveDTO(createReqVO)));
    }

    @PutMapping("/update")
    @Operation(summary = "修改模型端点（非秘密配置生成新版本）")
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:update')")
    public CommonResult<Boolean> updateEndpoint(@Valid @RequestBody AiModelEndpointSaveReqVO updateReqVO) {
        endpointService.updateEndpoint(toSaveDTO(updateReqVO));
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用模型端点")
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:update')")
    public CommonResult<Boolean> updateEndpointStatus(@Valid @RequestBody AiModelEndpointStatusReqVO reqVO) {
        endpointService.updateEndpointStatus(reqVO.getId(), reqVO.getVersion(), reqVO.getEnabled());
        return success(true);
    }

    @PutMapping("/rotate-credential")
    @Operation(summary = "轮换端点凭据（只递增凭据版本）")
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:update')")
    public CommonResult<Boolean> rotateCredential(@Valid @RequestBody AiModelEndpointCredentialReqVO reqVO) {
        endpointService.rotateCredential(reqVO.getId(), reqVO.getVersion(), reqVO.getCredential());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除模型端点（被引用时拒绝）")
    @Parameter(name = "id", description = "端点编号", required = true)
    @Parameter(name = "version", description = "乐观锁版本", required = true)
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:delete')")
    public CommonResult<Boolean> deleteEndpoint(
            @RequestParam("id") @NotNull @Positive Long id, @RequestParam("version") @NotNull Integer version) {
        endpointService.deleteEndpoint(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取模型端点")
    @Parameter(name = "id", description = "端点编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:query')")
    public CommonResult<AiModelEndpointRespVO> getEndpoint(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(endpointService.getEndpoint(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询模型端点")
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:query')")
    public CommonResult<PageResult<AiModelEndpointRespVO>> getEndpointPage(@Valid AiModelEndpointPageReqVO pageReqVO) {
        PageResult<AiModelEndpointDO> pageResult =
                endpointService.getEndpointPage(pageReqVO, pageReqVO.getName(), pageReqVO.getProvider());
        return success(new PageResult<>(
                pageResult.getList().stream()
                        .map(AiModelEndpointController::toRespVO)
                        .toList(),
                pageResult.getTotal()));
    }

    @GetMapping("/revisions")
    @Operation(summary = "查询端点的不可变配置版本")
    @Parameter(name = "id", description = "端点编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:model-endpoint:query')")
    public CommonResult<List<AiModelEndpointRevisionRespVO>> getRevisions(
            @RequestParam("id") @NotNull @Positive Long id) {
        return success(endpointService.getRevisions(id).stream()
                .map(AiModelEndpointController::toRevisionRespVO)
                .toList());
    }

    private static AiModelEndpointSaveDTO toSaveDTO(AiModelEndpointSaveReqVO reqVO) {
        AiModelEndpointSaveDTO saveDTO = new AiModelEndpointSaveDTO();
        saveDTO.setId(reqVO.getId());
        saveDTO.setName(reqVO.getName());
        saveDTO.setProvider(reqVO.getProvider());
        saveDTO.setBaseUrl(reqVO.getBaseUrl());
        saveDTO.setModelId(reqVO.getModelId());
        saveDTO.setCapabilities(reqVO.getCapabilities());
        saveDTO.setCredential(reqVO.getCredential());
        saveDTO.setVersion(reqVO.getVersion());
        return saveDTO;
    }

    private static AiModelEndpointRespVO toRespVO(AiModelEndpointDO endpoint) {
        AiModelEndpointRespVO respVO = new AiModelEndpointRespVO();
        respVO.setId(endpoint.getId());
        respVO.setName(endpoint.getName());
        respVO.setProvider(endpoint.getProvider());
        respVO.setBaseUrl(endpoint.getBaseUrl());
        respVO.setEnabled(endpoint.getEnabled());
        respVO.setReferenced(endpoint.getReferenced());
        respVO.setConfigRevision(endpoint.getConfigRevision());
        respVO.setCredentialRevision(endpoint.getCredentialRevision());
        respVO.setCredentialConfigured(
                endpoint.getCredentialRevision() != null && endpoint.getCredentialRevision() > 0);
        respVO.setVersion(endpoint.getVersion());
        respVO.setCreateTime(endpoint.getCreateTime());
        return respVO;
    }

    private static AiModelEndpointRevisionRespVO toRevisionRespVO(AiModelEndpointRevisionDO revision) {
        AiModelEndpointRevisionRespVO respVO = new AiModelEndpointRevisionRespVO();
        respVO.setRevision(revision.getRevision());
        respVO.setModelId(revision.getModelId());
        respVO.setCapabilities(
                StrUtil.isEmpty(revision.getCapabilities())
                        ? List.of()
                        : Arrays.asList(revision.getCapabilities().split(",")));
        respVO.setCreateTime(revision.getCreateTime());
        return respVO;
    }
}
