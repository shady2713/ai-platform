package com.basicframework.module.ai.controller.admin.grant;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.grant.vo.AiResourceGrantPageReqVO;
import com.basicframework.module.ai.controller.admin.grant.vo.AiResourceGrantRespVO;
import com.basicframework.module.ai.controller.admin.grant.vo.AiResourceGrantSaveReqVO;
import com.basicframework.module.ai.controller.admin.grant.vo.AiResourceGrantUpdateReqVO;
import com.basicframework.module.ai.dal.dataobject.grant.AiResourceGrantDO;
import com.basicframework.module.ai.service.authorization.AiResourceGrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 资源授权管理接口（A03）。
 *
 * <p>授权目录只做"主体 × 资源 × 动作白名单"的登记；判定入口在
 * {@code AiAuthorizationService}，撤销会递增授权版本使旧判定与历史指纹失效。
 * 权限码与 V52 迁移的 system_menu 种子一一对应。
 */
@Tag(name = "管理后台 - AI 资源授权")
@RestController
@RequestMapping("/ai/grant")
@Validated
@RequiredArgsConstructor
public class AiResourceGrantController {

    private final AiResourceGrantService grantService;

    @PostMapping("/create")
    @Operation(summary = "新增资源授权")
    @PreAuthorize("@ss.hasPermission('ai:grant:create')")
    public CommonResult<Long> createGrant(@Valid @RequestBody AiResourceGrantSaveReqVO createReqVO) {
        return success(grantService.createGrant(
                createReqVO.getApplicationId(),
                createReqVO.getSubjectType(),
                createReqVO.getExternalUserId(),
                createReqVO.getResourceType(),
                createReqVO.getResourceKey(),
                createReqVO.getActions()));
    }

    @PutMapping("/update")
    @Operation(summary = "修改资源授权动作白名单（递增授权版本）")
    @PreAuthorize("@ss.hasPermission('ai:grant:update')")
    public CommonResult<Boolean> updateGrant(@Valid @RequestBody AiResourceGrantUpdateReqVO updateReqVO) {
        grantService.updateGrant(updateReqVO.getId(), updateReqVO.getVersion(), updateReqVO.getActions());
        return success(true);
    }

    @PutMapping("/revoke")
    @Operation(summary = "撤销资源授权（立即失效并递增授权版本）")
    @PreAuthorize("@ss.hasPermission('ai:grant:revoke')")
    public CommonResult<Boolean> revokeGrant(
            @RequestParam("id") @NotNull @Positive Long id,
            @RequestParam("version") @NotNull @Positive Integer version) {
        grantService.revokeGrant(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "查询资源授权详情")
    @Parameter(name = "id", description = "授权编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:grant:query')")
    public CommonResult<AiResourceGrantRespVO> getGrant(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(grantService.getGrant(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "查询资源授权分页")
    @PreAuthorize("@ss.hasPermission('ai:grant:query')")
    public CommonResult<PageResult<AiResourceGrantRespVO>> getGrantPage(@Valid AiResourceGrantPageReqVO pageReqVO) {
        PageResult<AiResourceGrantDO> page = grantService.getGrantPage(
                pageReqVO,
                pageReqVO.getApplicationId(),
                pageReqVO.getSubjectType(),
                pageReqVO.getExternalUserId(),
                pageReqVO.getResourceType());
        List<AiResourceGrantRespVO> list =
                page.getList().stream().map(AiResourceGrantController::toRespVO).collect(Collectors.toList());
        return success(new PageResult<>(list, page.getTotal()));
    }

    private static AiResourceGrantRespVO toRespVO(AiResourceGrantDO grant) {
        Set<String> actions = grant.getActions() == null
                ? Set.of()
                : Arrays.stream(grant.getActions().split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        return new AiResourceGrantRespVO()
                .setId(grant.getId())
                .setApplicationId(grant.getApplicationId())
                .setSubjectType(grant.getSubjectType())
                .setExternalUserId(grant.getExternalUserId())
                .setResourceType(grant.getResourceType())
                .setResourceKey(grant.getResourceKey())
                .setActions(actions)
                .setStatus(grant.getStatus())
                .setAuthzRevision(grant.getAuthzRevision())
                .setVersion(grant.getVersion())
                .setCreateTime(grant.getCreateTime());
    }
}
