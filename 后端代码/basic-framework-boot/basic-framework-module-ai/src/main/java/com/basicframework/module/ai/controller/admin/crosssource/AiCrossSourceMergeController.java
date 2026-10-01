package com.basicframework.module.ai.controller.admin.crosssource;

import static com.basicframework.framework.common.pojo.CommonResult.success;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.module.ai.controller.admin.crosssource.vo.AiCrossSourceIntegrityRespVO;
import com.basicframework.module.ai.controller.admin.crosssource.vo.AiCrossSourceMergeRespVO;
import com.basicframework.module.ai.controller.admin.crosssource.vo.AiCrossSourceSourceAmountVO;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceIntegrity;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceResultContract;
import com.basicframework.module.ai.service.query.crosssource.AiCrossSourceMergeService;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceMergeQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨源合并结果对外入口（Y07）。
 *
 * <p>两个端点、两个权限码，与 V97 迁移的菜单种子一一对应：
 * 读结果 {@code ai:cross-source:query}、只读口径 {@code ai:cross-source:integrity}。
 * <b>每个端点恰好一个 {@code @PreAuthorize}</b>——鉴权口径不叠第二层，
 * 叠加会让"这个端点到底受哪个权限保护"无法从代码一眼读出来。
 *
 * <p>控制面权限（{@code @PreAuthorize}）与数据面授权（Y05 的逐级求交）是<b>两层</b>，
 * 缺一不可：前者回答"这个管理端操作者能不能调这个接口"，
 * 后者回答"这次被请求的跨源结果里有没有他无权看的来源"。
 * 端点<b>不</b>自己判定数据面授权——那会让授权口径散到 Controller，
 * 也会让两个端点出现两套判定结果。
 *
 * <p>为什么要有"只读口径"这个端点：它是本卡 fail-closed 承诺的可观测出口。
 * 界面可以先问"这份结果能不能给你看"，拿到口径后<b>再</b>决定要不要去取数字；
 * 在口径为 {@code WITHHELD} 的情况下，调用方从头到尾就没有经手过任何数字，
 * 连"服务端算出了一个数只是不给看"这件事都不会发生。
 */
@Tag(name = "管理后台 - AI 跨源合并结果")
@RestController
@RequestMapping("/ai/cross-source")
@Validated
@RequiredArgsConstructor
public class AiCrossSourceMergeController {

    private final AiCrossSourceMergeService mergeService;

    /**
     * 读取一次跨源执行的合并结果。
     *
     * <p>响应<b>恒带</b> {@code crossSource=true} 与 {@code crossSourceIntegrity}；
     * 口径为 {@code WITHHELD} 时响应里没有任何数字——合计、来源计数、分来源明细
     * 与时间点全部为 null/空。
     */
    @GetMapping("/results/{executionKey}")
    @Operation(summary = "读取跨源合并结果（恒带授权完整性口径；WITHHELD 时不含任何数字）")
    @PreAuthorize("@ss.hasPermission('ai:cross-source:query')")
    public CommonResult<AiCrossSourceMergeRespVO> result(
            @Parameter(description = "跨源执行幂等键", required = true) @NotBlank @RequestParam("executionKey")
                    String executionKey,
            @Parameter(description = "应用编号", required = true) @NotNull @Positive @RequestParam("applicationId")
                    Long applicationId,
            @Parameter(description = "主体类型（APP/USER）", required = true) @NotBlank @RequestParam("subjectType")
                    String subjectType,
            @Parameter(description = "可信外部用户标识", required = true) @NotBlank @RequestParam("externalUserId")
                    String externalUserId,
            @Parameter(description = "调用方的跨源角色（ANALYST/DATA_STEWARD/AGGREGATE_READER）", required = true)
                    @RequestParam("callerRoles")
                    List<String> callerRoles,
            @Parameter(description = "调用方此前已拿到过合计覆盖的来源角色（用于识别差额可解）")
                    @RequestParam(value = "previouslySeenRoles", required = false)
                    List<String> previouslySeenRoles) {
        return success(toRespVO(mergeService.merge(
                toQuery(executionKey, applicationId, subjectType, externalUserId, callerRoles, previouslySeenRoles))));
    }

    /**
     * 只读取授权完整性口径，<b>不返回任何数字</b>。
     *
     * <p>这是本卡 fail-closed 承诺的端点级体现：无论判定结论如何，
     * 本端点的响应里只可能出现 {@code state} 与 {@code reason} 两个字符串。
     * 界面据此决定要不要去取数字；口径为 {@code WITHHELD} 时，
     * 数字在服务端就从未被序列化过，而不是"发出去了又藏起来"。
     */
    @GetMapping("/results/{executionKey}/integrity")
    @Operation(summary = "只读跨源结果的授权完整性口径（响应中不含任何金额或条数）")
    @PreAuthorize("@ss.hasPermission('ai:cross-source:integrity')")
    public CommonResult<AiCrossSourceIntegrityRespVO> integrity(
            @Parameter(description = "跨源执行幂等键", required = true) @NotBlank @RequestParam("executionKey")
                    String executionKey,
            @Parameter(description = "应用编号", required = true) @NotNull @Positive @RequestParam("applicationId")
                    Long applicationId,
            @Parameter(description = "主体类型（APP/USER）", required = true) @NotBlank @RequestParam("subjectType")
                    String subjectType,
            @Parameter(description = "可信外部用户标识", required = true) @NotBlank @RequestParam("externalUserId")
                    String externalUserId,
            @Parameter(description = "调用方的跨源角色（ANALYST/DATA_STEWARD/AGGREGATE_READER）", required = true)
                    @RequestParam("callerRoles")
                    List<String> callerRoles,
            @Parameter(description = "调用方此前已拿到过合计覆盖的来源角色（用于识别差额可解）")
                    @RequestParam(value = "previouslySeenRoles", required = false)
                    List<String> previouslySeenRoles) {
        CrossSourceResultContract contract = mergeService.merge(
                toQuery(executionKey, applicationId, subjectType, externalUserId, callerRoles, previouslySeenRoles));
        // 刻意只投影口径两个字段：调用 toRespVO 会把金额带出来，那不是本端点的承诺。
        return success(toIntegrityVO(contract.integrity()));
    }

    /**
     * 组装服务层入参。角色名解析失败一律丢弃，最终得到空集合并由服务层 fail-closed 拒绝。
     *
     * <p>刻意不"就近取一个默认角色"：把认不出的角色名降级成最低角色，
     * 等于让一次拼写错误换回一份看起来能看的跨源结果。
     */
    private static CrossSourceMergeQuery toQuery(
            String executionKey,
            Long applicationId,
            String subjectType,
            String externalUserId,
            List<String> callerRoles,
            List<String> previouslySeenRoles) {
        Set<CrossSourceCallerRole> roles = callerRoles == null
                ? Set.of()
                : callerRoles.stream()
                        .map(CrossSourceCallerRole::parse)
                        .flatMap(Optional::stream)
                        .collect(Collectors.toUnmodifiableSet());
        Set<String> seen = previouslySeenRoles == null ? Set.of() : Set.copyOf(previouslySeenRoles);
        return new CrossSourceMergeQuery(executionKey, applicationId, subjectType, externalUserId, roles, seen);
    }

    /** 领域契约 → 响应 VO。VO 不反向被服务层引用（Controller VO 只属于协议层）。 */
    private static AiCrossSourceMergeRespVO toRespVO(CrossSourceResultContract contract) {
        List<AiCrossSourceSourceAmountVO> sources = contract.sources().stream()
                .map(source -> {
                    AiCrossSourceSourceAmountVO vo = new AiCrossSourceSourceAmountVO();
                    vo.setRole(source.role());
                    vo.setAmount(source.amount());
                    return vo;
                })
                .toList();
        AiCrossSourceMergeRespVO vo = new AiCrossSourceMergeRespVO();
        vo.setCrossSource(Boolean.TRUE);
        vo.setIntegrity(toIntegrityVO(contract.integrity()));
        vo.setExecutionKey(contract.executionKey());
        vo.setMetricCode(contract.metricCode());
        vo.setCurrency(contract.currency());
        vo.setTotalAmount(contract.totalAmount());
        vo.setSourceCount(contract.sourceCount());
        vo.setSources(sources);
        vo.setConsistencyAsOf(contract.consistencyAsOf());
        vo.setMaxSkewMillis(contract.maxSkewMillis());
        vo.setComplete(contract.complete());
        return vo;
    }

    /** 领域口径 → 响应 VO（两个端点共用，保证两条出口的口径形状逐字一致）。 */
    private static AiCrossSourceIntegrityRespVO toIntegrityVO(CrossSourceIntegrity integrity) {
        AiCrossSourceIntegrityRespVO vo = new AiCrossSourceIntegrityRespVO();
        vo.setState(integrity.state());
        vo.setReason(integrity.reason());
        return vo;
    }
}
