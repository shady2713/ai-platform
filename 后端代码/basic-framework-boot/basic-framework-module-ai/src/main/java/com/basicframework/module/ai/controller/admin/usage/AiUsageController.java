package com.basicframework.module.ai.controller.admin.usage;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.usage.vo.AiUsagePageReqVO;
import com.basicframework.module.ai.controller.admin.usage.vo.AiUsageRespVO;
import com.basicframework.module.ai.dal.dataobject.usage.AiUsageLedgerDO;
import com.basicframework.module.ai.service.quota.AiQuotaService;
import com.basicframework.module.ai.service.usage.AiUsageLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 用量与配额管理接口（Q02）。
 *
 * <p>权限码与 V81 菜单种子一致（{@code ai:usage:query}）。响应只含**计量元数据**：
 * 端点用引用（编号/别名）、token 未知时为空（读侧据此显示"未知/估算"，AT-060），
 * 不含提示词、响应正文、端点地址与密钥。
 */
@Tag(name = "管理后台 - AI 用量与限额")
@RestController
@RequestMapping("/ai/usage")
@Validated
@RequiredArgsConstructor
public class AiUsageController {

    private final AiUsageLedgerService usageLedgerService;

    private final AiQuotaService quotaService;

    @GetMapping("/page")
    @Operation(summary = "分页查询用量账本（按应用/服务/时间窗）")
    @PreAuthorize("@ss.hasPermission('ai:usage:query')")
    public CommonResult<PageResult<AiUsageRespVO>> page(@Valid AiUsagePageReqVO pageReqVO) {
        PageResult<AiUsageLedgerDO> page = usageLedgerService.page(
                pageReqVO,
                pageReqVO.getApplicationId(),
                pageReqVO.getServiceId(),
                pageReqVO.getFrom(),
                pageReqVO.getTo());
        return success(new PageResult<>(
                page.getList().stream().map(AiUsageController::toRespVO).toList(), page.getTotal()));
    }

    @GetMapping("/summary")
    @Operation(summary = "按计量来源聚合（含「来源未知」的条数，避免把估算当精确值）")
    @PreAuthorize("@ss.hasPermission('ai:usage:query')")
    public CommonResult<Map<String, Object>> summary(
            @Parameter(description = "应用编号", required = true) @RequestParam("applicationId") @NotNull @Positive
                    Long applicationId,
            @Parameter(description = "起始时间（含）", required = true)
                    @RequestParam("from")
                    @NotNull
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @Parameter(description = "结束时间（不含）", required = true)
                    @RequestParam("to")
                    @NotNull
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to) {
        return success(usageLedgerService.summaryBySource(applicationId, from, to));
    }

    @GetMapping("/service-summary")
    @Operation(summary = "按服务聚合用量")
    @PreAuthorize("@ss.hasPermission('ai:usage:query')")
    public CommonResult<List<Map<String, Object>>> serviceSummary(
            @Parameter(description = "应用编号", required = true) @RequestParam("applicationId") @NotNull @Positive
                    Long applicationId,
            @Parameter(description = "起始时间（含）", required = true)
                    @RequestParam("from")
                    @NotNull
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime from,
            @Parameter(description = "结束时间（不含）", required = true)
                    @RequestParam("to")
                    @NotNull
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime to) {
        return success(usageLedgerService.summaryByService(applicationId, from, to));
    }

    @GetMapping("/quota-active")
    @Operation(summary = "当前有效配额占位数（未释放且未到期；到期占位不计入）")
    @PreAuthorize("@ss.hasPermission('ai:usage:query')")
    public CommonResult<Long> quotaActive(
            @Parameter(description = "应用编号", required = true) @RequestParam("applicationId") @NotNull @Positive
                    Long applicationId) {
        return success(quotaService.activeCount(applicationId));
    }

    private static AiUsageRespVO toRespVO(AiUsageLedgerDO row) {
        return new AiUsageRespVO()
                .setApplicationId(row.getApplicationId())
                .setDurationMs(row.getDurationMs())
                .setEndpointRef(row.getEndpointRef())
                .setInputTokens(row.getInputTokens())
                .setInvocationId(row.getInvocationId())
                .setModelRef(row.getModelRef())
                .setModelRevision(row.getModelRevision())
                .setOccurredAt(row.getOccurredAt())
                .setOutputTokens(row.getOutputTokens())
                .setRunId(row.getRunId())
                .setServiceId(row.getServiceId())
                .setStatus(row.getStatus())
                .setTaskId(row.getTaskId())
                .setUsageSource(row.getUsageSource());
    }
}
