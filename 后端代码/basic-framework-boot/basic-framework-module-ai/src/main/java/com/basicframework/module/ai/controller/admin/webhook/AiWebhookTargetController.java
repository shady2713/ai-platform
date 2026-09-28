package com.basicframework.module.ai.controller.admin.webhook;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetPageReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetRespVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetRotateSecretReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetSaveReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookTargetStatusReqVO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookTargetDO;
import com.basicframework.module.ai.service.webhook.AiWebhookEventTypes;
import com.basicframework.module.ai.service.webhook.AiWebhookTargetService;
import com.basicframework.module.ai.service.webhook.dto.AiWebhookTargetSaveDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
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
 * Webhook 投递目标管理接口（X10）。
 *
 * <p>密钥边界：查询与修改响应**只有** {@code secretConfigured}/{@code secretRevision}，
 * 不含明文与密文；明文只在提交的入参里出现，落库是 CredentialCipher 密文。
 * 停用（DISABLED）即停发：不再产生新投递、发送前复检被拒、人工重投被拒。
 * 权限码与 V88 迁移的 system_menu 种子一一对应。
 */
@Tag(name = "管理后台 - AI Webhook 目标")
@RestController
@RequestMapping("/ai/webhook/target")
@Validated
@RequiredArgsConstructor
public class AiWebhookTargetController {

    private final AiWebhookTargetService targetService;

    @PostMapping("/create")
    @Operation(summary = "创建 Webhook 目标（签名密钥只提交不回显）")
    @PreAuthorize("@ss.hasPermission('ai:webhook:manage')")
    public CommonResult<Long> createTarget(@Valid @RequestBody AiWebhookTargetSaveReqVO createReqVO) {
        return success(targetService.create(toSaveDTO(createReqVO)));
    }

    @PutMapping("/update")
    @Operation(summary = "修改 Webhook 目标（标识不可修改；密钥留空表示保留）")
    @PreAuthorize("@ss.hasPermission('ai:webhook:manage')")
    public CommonResult<Boolean> updateTarget(@Valid @RequestBody AiWebhookTargetSaveReqVO updateReqVO) {
        targetService.update(toSaveDTO(updateReqVO));
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "启用/停用 Webhook 目标（停用即停发）")
    @PreAuthorize("@ss.hasPermission('ai:webhook:manage')")
    public CommonResult<Boolean> updateStatus(@Valid @RequestBody AiWebhookTargetStatusReqVO reqVO) {
        targetService.updateStatus(reqVO.getId(), reqVO.getVersion(), reqVO.getEnabled());
        return success(true);
    }

    @PutMapping("/rotate-secret")
    @Operation(summary = "轮换签名密钥（旧密钥立即作废，响应不含密钥材料）")
    @PreAuthorize("@ss.hasPermission('ai:webhook:rotate')")
    public CommonResult<Boolean> rotateSecret(@Valid @RequestBody AiWebhookTargetRotateSecretReqVO reqVO) {
        targetService.rotateSecret(reqVO.getId(), reqVO.getVersion(), reqVO.getSecret());
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除 Webhook 目标（在途投递会在发送前复检失败并按事实收尾）")
    @PreAuthorize("@ss.hasPermission('ai:webhook:delete')")
    public CommonResult<Boolean> deleteTarget(
            @RequestParam("id") @NotNull @Positive Long id,
            @RequestParam("version") @NotNull @PositiveOrZero Integer version) {
        targetService.delete(id, version);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获取 Webhook 目标")
    @Parameter(name = "id", description = "目标编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:webhook:query')")
    public CommonResult<AiWebhookTargetRespVO> getTarget(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(targetService.get(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询 Webhook 目标")
    @PreAuthorize("@ss.hasPermission('ai:webhook:query')")
    public CommonResult<PageResult<AiWebhookTargetRespVO>> getTargetPage(@Valid AiWebhookTargetPageReqVO pageReqVO) {
        PageResult<AiWebhookTargetDO> page = targetService.getPage(
                pageReqVO, pageReqVO.getApplicationId(), pageReqVO.getCode(), pageReqVO.getStatus());
        return success(new PageResult<>(
                page.getList().stream().map(AiWebhookTargetController::toRespVO).toList(), page.getTotal()));
    }

    private static AiWebhookTargetSaveDTO toSaveDTO(AiWebhookTargetSaveReqVO reqVO) {
        return new AiWebhookTargetSaveDTO()
                .setId(reqVO.getId())
                .setApplicationId(reqVO.getApplicationId())
                .setCode(reqVO.getCode())
                .setName(reqVO.getName())
                .setTargetUrl(reqVO.getTargetUrl())
                .setEventTypes(reqVO.getEventTypes())
                .setSecret(reqVO.getSecret())
                .setMaxAttempts(reqVO.getMaxAttempts())
                .setVersion(reqVO.getVersion());
    }

    private static AiWebhookTargetRespVO toRespVO(AiWebhookTargetDO target) {
        List<String> eventTypes = AiWebhookEventTypes.parse(target.getEventTypes());
        return new AiWebhookTargetRespVO()
                .setId(target.getId())
                .setApplicationId(target.getApplicationId())
                .setCode(target.getCode())
                .setName(target.getName())
                .setTargetUrl(target.getTargetUrl())
                .setEventTypes(eventTypes)
                .setSecretConfigured(StringUtils.hasText(target.getSecretCiphertext()))
                .setSecretRevision(target.getSecretRevision())
                .setStatus(target.getStatus())
                .setMaxAttempts(target.getMaxAttempts())
                .setVersion(target.getVersion())
                .setCreateTime(target.getCreateTime());
    }
}
