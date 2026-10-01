package com.basicframework.module.ai.service.query.crosssource;

import com.basicframework.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionDO;
import com.basicframework.module.ai.dal.dataobject.crosssource.AiCrossSourceExecutionSourceDO;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceExecutionMapper;
import com.basicframework.module.ai.dal.mysql.crosssource.AiCrossSourceSourceContributionMapper;
import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceAuthorizationJudge;
import com.basicframework.module.ai.service.authorization.crosssource.AiCrossSourceContractErrors;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFacts;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFactsResolver;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFactsResolver.CrossSourceFactsQuery;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFactsResolver.SourceBinding;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceIntegrity;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceResultContract;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceResultContract.SourceAmount;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 跨源合并结果的对外产出端（Y07）：**读 Y04 的台账、过 Y05 的守卫、产出恒带口径的响应契约**。
 *
 * <p>本服务刻意<b>不执行</b>跨源查询——执行是 Y04 的职责，入参是计划与取数规格，
 * 属于查询链路的内部。对外入口需要的是另一件事：把<b>已经算完的一次执行</b>，
 * 在<b>调用方当前授权下</b>组装成一份可交付的响应。因此这里只做三件事：
 *
 * <ol>
 *   <li>按执行键读台账（Y04 的执行记录 + 每来源一行），并把台账里的来源清单当作
 *       "计划声明的来源"——判定范围来自台账事实而非请求参数；</li>
 *   <li>用当前真实授权解析事实（{@link CrossSourceAccessFactsResolver}），
 *       交 Y05 的 {@code judge} 逐级求交——授权口径完全复用 Y05，不另起一套；</li>
 *   <li>按判定结论产出 {@link CrossSourceResultContract}，其口径字段<b>恒非空</b>。</li>
 * </ol>
 *
 * <p><b>撤销后立即不可读</b>：判定读的是当前授权而非执行时的授权快照，
 * 因此授权在执行之后被撤销时本次读取会被拒，而不是"执行时有权就一直能看"。
 *
 * <p><b>不复述 Y05 的拒绝</b>：{@code judge} 与两条披露闸门抛出的稳定编号
 * （来源无权、映射无权、差额可解、条数可数）原样向上传播。
 * 本服务只负责"判定通过之后响应长什么样"，不把拒绝重新包装成一种成功响应——
 * 那会让调用方在"没权限"与"成功了但没数据"之间误判处置动作。
 */
@Service
@RequiredArgsConstructor
public class AiCrossSourceMergeService {

    /**
     * 只有数据管理员能看到分来源明细（{@code source_system} 是该角色独有的字段）。
     *
     * <p>用于区分 {@code COMPLETE} 与 {@code PARTIAL}：合计在两种口径下都可出
     * （它已被 Y05 的披露闸门判定为不可反推），差别只在<b>分来源明细</b>这一层。
     */
    private static final String FIELD_SOURCE_SYSTEM = "source_system";

    /** 角色不允许查看分来源明细时的静态理由。 */
    private static final String PARTIAL_REASON = "调用方角色不允许查看分来源明细，本次仅出具合计口径。";

    private final AiCrossSourceExecutionMapper executionMapper;

    private final AiCrossSourceSourceContributionMapper contributionMapper;

    private final AiCrossSourceAuthorizationJudge judge;

    private final CrossSourceAccessFactsResolver factsResolver;

    /**
     * 按执行键产出跨源响应契约。
     *
     * @param query 主体上下文 + 执行键 + 调用方角色 + 此前已见过的来源集合
     * @return 恒带完整性口径的响应契约；口径为 {@code WITHHELD} 时不含任何数字
     */
    public CrossSourceResultContract merge(CrossSourceMergeQuery query) {
        requireValid(query);
        AiCrossSourceExecutionDO execution = executionMapper.selectByExecutionKey(query.executionKey());
        if (execution == null) {
            throw AiCrossSourceContractErrors.executionNotExists();
        }
        List<AiCrossSourceExecutionSourceDO> ledger = loadLedger(execution.getId());
        List<AiCrossSourceExecutionSourceDO> counted = countedRows(ledger);
        if (!issuable(execution, counted)) {
            // 技术性不可出具：受控结束（FAILED）、仍在跑、或一个来源都没计入。
            // 报 409 稳定编号而不是出 WITHHELD——Y05 的 WITHHELD 提示写的是
            // "请联系管理员开通授权"，用它表达一次执行失败会把用户引向错误的处置动作。
            // 处置动作在这里是"修数据或换执行键重跑"，与授权失败截然不同。
            throw AiCrossSourceContractErrors.resultNotIssuable();
        }
        if (!bindingsResolvable(ledger)) {
            // 台账读到了，但某条来源行没有数据集绑定 → 无法建立授权事实 →
            // 这份响应给不出可断言的授权口径。按 Y07 的规定：缺口径即 WITHHELD。
            return CrossSourceResultContract.missingIntegrity(execution.getExecutionKey());
        }

        List<String> planSources = rolesOf(ledger);
        Map<String, CrossSourceAccessFacts> facts = factsResolver.resolve(toFactsQuery(query, ledger));
        // Y05 守卫：逐级求交，任一级无权即拒。拒绝编号原样传播，不在本层改写。
        var grant = judge.judge(planSources, facts, query.callerRoles(), query.previouslySeenRoles());
        List<String> visibleSources =
                counted.stream().map(AiCrossSourceExecutionSourceDO::getRole).toList();
        // 两条披露闸门：差额可解与条数可数。判定通过后 deniedSources 必为空，
        // 但仍显式复检——"判定时通过"不等于"披露时安全"，闸门必须落在出口。
        judge.requireDisclosableTotal(
                execution.getTotalAmount(), visibleSources, Set.of(), query.previouslySeenRoles());
        int sourceCount = judge.discloseSourceCount(visibleSources, Set.of());

        // 口径是授权维度的结论，与 Y04 的技术完整性（completeness）分开表达。
        // COMPLETE：角色允许查看分来源明细。PARTIAL：只出合计，不出分来源明细。
        boolean breakdownVisible = grant.exposes(FIELD_SOURCE_SYSTEM);
        CrossSourceIntegrity integrity =
                breakdownVisible ? CrossSourceIntegrity.complete() : CrossSourceIntegrity.partial(PARTIAL_REASON);
        return CrossSourceResultContract.disclosable(
                execution.getExecutionKey(),
                execution.getMetricCode(),
                execution.getCurrency(),
                execution.getTotalAmount(),
                sourceCount,
                breakdownVisible ? sourceAmounts(counted) : List.of(),
                execution.getConsistencyAsOf(),
                execution.getMaxSkewMillis(),
                true,
                integrity);
    }

    /** 入参 fail-closed：主体上下文缺失时无法确定授权事实，查不到授权不等于仍然有权。 */
    private static void requireValid(CrossSourceMergeQuery query) {
        if (query == null
                || query.executionKey() == null
                || query.executionKey().isBlank()
                || query.applicationId() == null
                || query.subjectType() == null
                || query.subjectType().isBlank()
                || query.externalUserId() == null
                || query.externalUserId().isBlank()
                || query.callerRoles().isEmpty()) {
            throw AiCrossSourceContractErrors.requestInvalid();
        }
    }

    /**
     * 读这次执行的<b>全部</b>来源行（含 MISSING/FAILED），不只读 COUNTED。
     *
     * <p>判定范围必须是台账里出现过的全部来源：只按已计入的来源判定，
     * 会让"无权但恰好取数失败"的来源从判定里消失——而它仍然是这次合并的一部分，
     * 把它排除等于给无权来源开了一条"只要它失败就绕过判定"的路。
     */
    private List<AiCrossSourceExecutionSourceDO> loadLedger(Long executionId) {
        return contributionMapper.selectList(new LambdaQueryWrapperX<AiCrossSourceExecutionSourceDO>()
                .eq(AiCrossSourceExecutionSourceDO::getExecutionId, executionId)
                .eq(AiCrossSourceExecutionSourceDO::getDeleted, false)
                .orderByAsc(AiCrossSourceExecutionSourceDO::getRole));
    }

    /**
     * 结果是否可出具：必须是成功终态、有合计、有口径时间点，且至少计入了一个来源。
     *
     * <p>缺任何一项都不出数：没有合计的"跨源结果"只是一个执行记录；
     * 没有 {@code consistencyAsOf} 的合计无法解释成任何一个时刻的数（Y04 语义）。
     */
    private static boolean issuable(AiCrossSourceExecutionDO execution, List<AiCrossSourceExecutionSourceDO> counted) {
        return AiCrossSourceExecutionDO.STATUS_SUCCEEDED.equals(execution.getStatus())
                && execution.getTotalAmount() != null
                && execution.getConsistencyAsOf() != null
                && !counted.isEmpty();
    }

    /**
     * 台账里的来源行是否都能定位到数据集。
     *
     * <p>没有数据集绑定就建立不了授权事实，也就给不出可断言的授权口径。
     * 这时既不能出数（可能被禁来源混在里面），也不能报"请去申请权限"
     * （用户无从申请一个不存在的绑定），只能按口径缺失落 {@code WITHHELD}。
     */
    private static boolean bindingsResolvable(List<AiCrossSourceExecutionSourceDO> ledger) {
        for (AiCrossSourceExecutionSourceDO row : ledger) {
            if (row.getDatasetCode() == null || row.getDatasetCode().isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** 已计入合计的来源行（可披露范围；缺失来源不按 0 补齐）。 */
    private static List<AiCrossSourceExecutionSourceDO> countedRows(List<AiCrossSourceExecutionSourceDO> ledger) {
        List<AiCrossSourceExecutionSourceDO> counted = new ArrayList<>();
        for (AiCrossSourceExecutionSourceDO row : ledger) {
            if (AiCrossSourceExecutionSourceDO.STATUS_COUNTED.equals(row.getStatus())) {
                counted.add(row);
            }
        }
        return List.copyOf(counted);
    }

    /** 全部出现过的来源角色（判定范围；顺序稳定，便于断言）。 */
    private static List<String> rolesOf(List<AiCrossSourceExecutionSourceDO> ledger) {
        Set<String> roles = new LinkedHashSet<>();
        for (AiCrossSourceExecutionSourceDO row : ledger) {
            roles.add(row.getRole());
        }
        return List.copyOf(roles);
    }

    /** 分来源明细：只含角色与金额，不含数据集编号、来源系统与实体键。 */
    private static List<SourceAmount> sourceAmounts(List<AiCrossSourceExecutionSourceDO> counted) {
        return counted.stream()
                .map(row -> new SourceAmount(row.getRole(), row.getAmount()))
                .toList();
    }

    private static CrossSourceFactsQuery toFactsQuery(
            CrossSourceMergeQuery query, List<AiCrossSourceExecutionSourceDO> ledger) {
        List<SourceBinding> bindings = ledger.stream()
                .map(row -> new SourceBinding(row.getRole(), row.getDatasetCode()))
                .toList();
        return new CrossSourceFactsQuery(query.applicationId(), query.subjectType(), query.externalUserId(), bindings);
    }
}
