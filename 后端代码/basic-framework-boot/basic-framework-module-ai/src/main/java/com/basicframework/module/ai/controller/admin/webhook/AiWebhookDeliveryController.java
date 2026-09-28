package com.basicframework.module.ai.controller.admin.webhook;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookDeliveryAttemptRespVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookDeliveryPageReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookDeliveryRedeliverReqVO;
import com.basicframework.module.ai.controller.admin.webhook.vo.AiWebhookDeliveryRespVO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryAttemptDO;
import com.basicframework.module.ai.dal.dataobject.webhook.AiWebhookDeliveryDO;
import com.basicframework.module.ai.service.webhook.AiWebhookDeliveryService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook 投递管理接口（X10）：查看投递与尝试留痕、死信人工重投。
 *
 * <p>只读事实：状态、计数、稳定原因码与耗时；**不返回**投递正文、目标地址与签名密钥。
 * 人工重投只对死信（FAILED）生效，且目标必须存在并启用——停用即停发是硬约束。
 */
@Tag(name = "管理后台 - AI Webhook 投递")
@RestController
@RequestMapping("/ai/webhook/delivery")
@Validated
@RequiredArgsConstructor
public class AiWebhookDeliveryController {

    private final AiWebhookDeliveryService deliveryService;

    @GetMapping("/get")
    @Operation(summary = "获取 Webhook 投递")
    @Parameter(name = "id", description = "投递编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:webhook:query')")
    public CommonResult<AiWebhookDeliveryRespVO> getDelivery(@RequestParam("id") @NotNull @Positive Long id) {
        return success(toRespVO(deliveryService.get(id)));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询 Webhook 投递（按状态过滤 FAILED 即死信视图）")
    @PreAuthorize("@ss.hasPermission('ai:webhook:query')")
    public CommonResult<PageResult<AiWebhookDeliveryRespVO>> getDeliveryPage(
            @Valid AiWebhookDeliveryPageReqVO pageReqVO) {
        PageResult<AiWebhookDeliveryDO> page = deliveryService.getPage(
                pageReqVO, pageReqVO.getTargetId(), pageReqVO.getStatus(), pageReqVO.getEventType());
        return success(new PageResult<>(
                page.getList().stream()
                        .map(AiWebhookDeliveryController::toRespVO)
                        .toList(),
                page.getTotal()));
    }

    @GetMapping("/attempts")
    @Operation(summary = "获取某次投递的尝试留痕（每一次尝试的结论与耗时）")
    @Parameter(name = "deliveryId", description = "投递编号", required = true)
    @PreAuthorize("@ss.hasPermission('ai:webhook:query')")
    public CommonResult<List<AiWebhookDeliveryAttemptRespVO>> getAttempts(
            @RequestParam("deliveryId") @NotNull @Positive Long deliveryId) {
        return success(deliveryService.getAttempts(deliveryId).stream()
                .map(AiWebhookDeliveryController::toAttemptRespVO)
                .toList());
    }

    @PutMapping("/redeliver")
    @Operation(summary = "人工重投死信（重置尝试预算并立即入队；目标停用或已删除时拒绝）")
    @PreAuthorize("@ss.hasPermission('ai:webhook:redeliver')")
    public CommonResult<Boolean> redeliver(@Valid @RequestBody AiWebhookDeliveryRedeliverReqVO reqVO) {
        deliveryService.redeliver(reqVO.getId());
        return success(true);
    }

    private static AiWebhookDeliveryRespVO toRespVO(AiWebhookDeliveryDO delivery) {
        return new AiWebhookDeliveryRespVO()
                .setId(delivery.getId())
                .setDeliveryNo(delivery.getDeliveryNo())
                .setTargetId(delivery.getTargetId())
                .setApplicationId(delivery.getApplicationId())
                .setEventType(delivery.getEventType())
                .setResourceType(delivery.getResourceType())
                .setResourceId(delivery.getResourceId())
                .setResourceKey(delivery.getResourceKey())
                .setOccurredTime(delivery.getOccurredTime())
                .setPayloadDigest(delivery.getPayloadDigest())
                .setStatus(delivery.getStatus())
                .setAttemptCount(delivery.getAttemptCount())
                .setMaxAttempts(delivery.getMaxAttempts())
                .setLastErrorCode(delivery.getLastErrorCode())
                .setFailureCode(delivery.getFailureCode())
                .setNextAttemptTime(delivery.getNextAttemptTime())
                .setFirstAttemptTime(delivery.getFirstAttemptTime())
                .setDeliveredTime(delivery.getDeliveredTime())
                .setCreateTime(delivery.getCreateTime());
    }

    private static AiWebhookDeliveryAttemptRespVO toAttemptRespVO(AiWebhookDeliveryAttemptDO attempt) {
        return new AiWebhookDeliveryAttemptRespVO()
                .setAttemptNo(attempt.getAttemptNo())
                .setOutcome(attempt.getOutcome())
                .setErrorCode(attempt.getErrorCode())
                .setHttpStatus(attempt.getHttpStatus())
                .setSignatureTimestamp(attempt.getSignatureTimestamp())
                .setDurationMs(attempt.getDurationMs())
                .setStartedTime(attempt.getStartedTime())
                .setFinishedTime(attempt.getFinishedTime());
    }
}
