package com.basicframework.module.ai.controller.admin.application;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationCredentialIssueRespVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationPageReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationRespVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationSaveReqVO;
import com.basicframework.module.ai.controller.admin.application.vo.AiApplicationStatusReqVO;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.domain.application.ApplicationOrigins;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.application.dto.AiApplicationCredentialIssueDTO;
import com.basicframework.module.ai.service.application.dto.AiApplicationSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
 * AI 应用管理接口（A01）。
 *
 * <p>凭据边界：查询与修改响应**只有** {@code credentialConfigured} 标识，
 * 不含摘要与明文；明文只在创建与轮换的响应里出现一次（AT-011）。
 * 权限码与 V50 迁移的 system_menu 种子一一对应。
 */
@Tag(name = "管理后台 - AI 应用")
@RestController
@RequestMapping("/ai/application")
@Validated
@RequiredArgsConstructor
public class AiApplicationController {

    private final AiApplicationService applicationService;

    @PostMapping("/create")
    @Operation(summary = "创建应用（响应携带一次性客户端秘密）")
    @PreAuthorize("@ss.hasPermission('ai:application:create')")
    public CommonResult<AiApplicationCredentialIssueRespVO> createApplication(
            @Valid @RequestBody AiApplicationSaveReqVO createReqVO) {
        return success(toIssueRespVO(applicationService.createApplication(toSaveDTO(createReqVO))));
    }

    @PutMapping("/update")
    @Operation(summary = "修改应用（appCode 不可修改）")
    @PreAuthorize("@ss.hasPermission('ai:application:update')")
    public CommonResult<Boolean> updateApplication(@Valid @RequestBody AiApplicationSaveReqVO updateReqVO) {
        applicationService.updateApplication(toSaveDTO(updateReqVO));
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用应用")
    @PreAuthorize("@ss.hasPermission('ai:application:update')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody AiApplicationStatusReqVO reqVO) {
        applicationService.updateStatus(reqVO.getId(), reqVO.getVersion(), reqVO.getEnabled());
        return success(true);
    }

    @PutMapping("/rotate-credential")
    @Operation(summary = "轮换客户端凭据（旧凭据立即失效，响应携带新秘密）")
    @PreAuthorize("@ss.hasPermission('ai:application:rotate')")
    public CommonResult<AiApplicationCredentialIssueRespVO> rotateCredential(
            @RequestParam("id") @NotNull @Positive Long id,
            @RequestParam("version") @NotNull @Positive Integer version) {
        return success(toIssueRespVO(applicationService.rotateCredential(id, version)));
    }

    @PutMapping("/revoke-credential")
    @Operation(summary = "吊销客户端凭据（不签发新凭据）")
    @PreAuthorize("@ss.hasPermission('ai:application:revoke')")
    public CommonResult<Boolean> revokeCredential(
            @RequestParam("id") @NotNull @Positive Long id,
            @RequestParam("version") @NotNull @Positive Integer version) {
        applicationService.revokeCredential(id, version);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除应用（必须先吊销全部凭据）")
    @PreAuthorize("@ss.hasPermission('ai:application:delete')")
    public CommonResult<Boolean> deleteApplication(
            @RequestParam("id") @NotNull @Positive Long id,
            @RequestParam("version") @NotNull @Positive Integer version) {
        applicationService.deleteApplication(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "查询应用详情")
    @Parameter(name = "id", description = "应用编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:application:query')")
    public CommonResult<AiApplicationRespVO> getApplication(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(applicationService.getApplication(id), applicationService.hasActiveCredential(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "查询应用分页")
    @PreAuthorize("@ss.hasPermission('ai:application:query')")
    public CommonResult<PageResult<AiApplicationRespVO>> getApplicationPage(@Valid AiApplicationPageReqVO pageReqVO) {
        PageResult<AiApplicationDO> page =
                applicationService.getApplicationPage(pageReqVO, pageReqVO.getAppCode(), pageReqVO.getEnabled());
        // 列表只展示应用本身：凭据状态在详情里给出，避免逐行回查造成 N+1
        List<AiApplicationRespVO> list = page.getList().stream()
                .map(application -> toRespVO(application, null))
                .toList();
        return success(new PageResult<>(list, page.getTotal()));
    }

    private static AiApplicationSaveDTO toSaveDTO(AiApplicationSaveReqVO reqVO) {
        return new AiApplicationSaveDTO()
                .setId(reqVO.getId())
                .setAppCode(reqVO.getAppCode())
                .setName(reqVO.getName())
                .setDescription(reqVO.getDescription())
                .setOrigins(reqVO.getOrigins())
                .setVersion(reqVO.getVersion());
    }

    private static AiApplicationRespVO toRespVO(AiApplicationDO application, Boolean credentialConfigured) {
        return new AiApplicationRespVO()
                .setId(application.getId())
                .setAppCode(application.getAppCode())
                .setName(application.getName())
                .setDescription(application.getDescription())
                .setOrigins(ApplicationOrigins.parse(application.getOrigins()))
                .setEnabled(application.getEnabled())
                .setCredentialConfigured(credentialConfigured)
                .setVersion(application.getVersion())
                .setCreateTime(application.getCreateTime());
    }

    private static AiApplicationCredentialIssueRespVO toIssueRespVO(AiApplicationCredentialIssueDTO issue) {
        return new AiApplicationCredentialIssueRespVO()
                .setApplicationId(issue.getApplication().getId())
                .setAppCode(issue.getApplication().getAppCode())
                .setCredentialId(issue.getCredentialId())
                .setSecret(issue.getSecret());
    }
}
