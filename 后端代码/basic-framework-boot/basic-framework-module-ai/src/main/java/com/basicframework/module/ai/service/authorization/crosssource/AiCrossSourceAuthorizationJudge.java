package com.basicframework.module.ai.service.authorization.crosssource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨源授权判定（Y05）：**逐级求交 → 合计泄漏判定 → 可见字段裁剪**。
 *
 * <p>本类是三条专项的共同落点，三条都必须在**出具任何数字之前**完成：
 * <ol>
 *   <li><b>专项一（合计不泄漏明细）</b>：把"有权来源"与"被禁来源"分开之后，
 *       只要口径里存在被禁来源，本次合计就<b>不得</b>出具——不是因为合计算错，
 *       而是因为"总额 = 有权部分 + 被禁部分"这个等式一旦公开，调用方拿有权部分做减法
 *       就能解出被禁部分。判定因此是<b>整体拒绝</b>而不是"给个偏小的数"。</li>
 *   <li><b>专项二（映射无权也拒绝）</b>：{@link CrossSourceAccessFacts#mappingAuthorized}
 *       与来源可读性<b>同级</b>求交。计划合法、两个数据集都能读，只要映射无权，
 *       关联仍然被拒——"我只是在做关联"不构成豁免理由。</li>
 *   <li><b>专项三（捕获无失权数据）</b>：由 {@link CrossSourceModelInputCaptureVerifier}
 *       在<b>读取捕获时</b>复核，不是在写入时过滤。</li>
 * </ol>
 *
 * <p><b>拒绝不可区分</b>：拒绝路径上的错误码会指明"哪一级"无权（系统/数据集/映射），
 * 但消息是静态的——不携带被拒角色的名字，更不携带其规模、行数或取值。
 * 角色名本身就是业务信息（"payment" 会告诉调用方存在一个回款来源），
 * 拒绝只需要说"哪一级无权"，不需要说"是谁"。
 *
 * <p>本类不执行查询、不访问数据库、不持有可变状态：它是把"授权事实"映射成
 * "可否出具"的<b>纯函数</b>，因此同一组事实永远得到同一结论，可被直接断言。
 */
public final class AiCrossSourceAuthorizationJudge {

    /**
     * 判定一次跨源合并是否可以出具合计。
     *
     * @param planSources    计划声明的来源角色清单（口径事实，不是用户输入）
     * @param factsByRole    逐角色的授权事实（与 {@code planSources} 一一对应）
     * @param callerRoles    调用方在本应用下具备的角色（取交集，决定可见字段）
     * @param previouslySeen 调用方**此前已经拿到过**的角色集合：用于识别"差额可解"
     * @return 判定结果（只有全部通过才非空）
     * @throws com.basicframework.framework.common.exception.ServiceException 任一级不通过
     */
    public CrossSourceGrant judge(
            List<String> planSources,
            Map<String, CrossSourceAccessFacts> factsByRole,
            Set<CrossSourceCallerRole> callerRoles,
            Set<String> previouslySeen) {
        requireValid(planSources, factsByRole, callerRoles);
        // 1) 逐级求交：系统 → 数据集 → 映射。任一级缺失即无权。
        //    事实缺失已由 requireValid 先行拒绝（fail-closed），故此处 facts 必非空。
        Set<String> deniedRoles = new LinkedHashSet<>();
        Set<String> mappingDeniedRoles = new LinkedHashSet<>();
        for (String role : planSources) {
            CrossSourceAccessFacts facts = factsByRole.get(role);
            if (!facts.sourceReadable()) {
                deniedRoles.add(role);
            } else if (!facts.mappingAuthorized()) {
                // 映射无权单独归类：数据可读但不允许关联，处置是"申请映射权限"，
                // 与"申请数据权限"不是同一件事，编号也必须不同（专项二）
                mappingDeniedRoles.add(role);
            }
        }
        if (!deniedRoles.isEmpty() || !mappingDeniedRoles.isEmpty()) {
            // 映射无权优先报：调用方最需要知道的是"关联本身不被允许"
            if (!mappingDeniedRoles.isEmpty() && deniedRoles.isEmpty()) {
                throw AiCrossSourceAuthorizationErrors.mappingNotAuthorized();
            }
            throw denied(deniedRoles, previouslySeen);
        }
        return new CrossSourceGrant(
                List.copyOf(planSources),
                CrossSourceCallerRole.commonFields(callerRoles),
                Set.copyOf(callerRoles),
                true);
    }

    /**
     * 撤销后的重放判定：调用方**曾经**有权，本次已失权。
     *
     * <p>这是专项一最强的一条：即使平台从不出具"含被禁来源的合计"，
     * 只要历史产物里存在过一个包含被禁来源的合计，调用方拿它减去现在的有权合计
     * 就能解出被禁来源的取值。因此失权后**任何**跨源合计都不再出具——
     * 不是"过滤掉被禁部分"，而是整个口径对该主体关闭。
     *
     * @param planSources    计划声明的来源角色
     * @param lostRoles      本次已失权的角色（历史上有权、现在无权）
     * @param previouslySeen 调用方此前已拿到合计覆盖的角色
     * @return 永不返回成功：要么通过（完全没失权），要么抛稳定错误码
     */
    public CrossSourceGrant judgeAfterRevocation(
            List<String> planSources, Set<String> lostRoles, Set<String> previouslySeen) {
        requireValidSources(planSources);
        if (lostRoles == null || lostRoles.isEmpty()) {
            return new CrossSourceGrant(
                    List.copyOf(planSources), CrossSourceCallerRole.commonFields(Set.of()), Set.of(), true);
        }
        Set<String> alsoSeen = intersect(lostRoles, previouslySeen);
        // 失权且此前见过 → 差额可解，直接拒绝
        if (!alsoSeen.isEmpty()) {
            throw AiCrossSourceAuthorizationErrors.totalExposesForbiddenDetail();
        }
        // 失权但从未见过该来源的数字：拒绝仍然成立，但原因是"无权"而不是"可反推"，
        // 两者的处置相同（不出具），错误码不同，便于调用方区分该去申请授权还是该销毁旧产物。
        throw AiCrossSourceAuthorizationErrors.sourceNotAuthorized();
    }

    /**
     * 条数泄漏判定（专项一的第二条腿）：来源计数本身也是信道。
     *
     * <p>被禁来源即使一个数字都不出，只要告诉调用方"这次合并了 3 个来源"，
     * 它就能反推"有 1 个来源我看不到"，进而结合口径清单定位到被禁的是哪一个。
     * 因此只要存在被禁来源，来源计数与角色清单都不随之出具。
     *
     * @param visibleSources 本次可见的来源角色
     * @param deniedSources  被禁的来源角色（数量本身不得外泄）
     * @return 可安全披露的来源数（等于全部来源数——存在被禁来源时直接拒绝）
     */
    public int discloseSourceCount(List<String> visibleSources, Set<String> deniedSources) {
        if (deniedSources != null && !deniedSources.isEmpty()) {
            throw AiCrossSourceAuthorizationErrors.sourceCountLeaksForbidden();
        }
        return visibleSources == null ? 0 : visibleSources.size();
    }

    /**
     * 合计是否可按给定金额出具（把"差额可解"与"条数可数"两条腿放在一起判定）。
     *
     * <p>差额可解的判据是<b>历史覆盖范围 ⊋ 本次覆盖范围</b>：调用方手上有一个覆盖
     * {@code previouslySeen} 的旧合计，现在又拿到一个覆盖 {@code visibleSources} 的新合计，
     * 只要旧集合里有本次不再出现的来源，两次相减就解出了那个来源。
     *
     * <p>注意判据的方向：危险的是"曾经见过、现在不出现"，而不是"曾经见过、现在还在"。
     * 后者是同一次口径的正常重算，不构成泄漏——把它一并拒绝会把功能关死，
     * 而真正的风险恰恰在方向弄反时**静默失效**。
     */
    public void requireDisclosableTotal(
            BigDecimal total, List<String> visibleSources, Set<String> deniedSources, Set<String> previouslySeen) {
        if (deniedSources != null && !deniedSources.isEmpty()) {
            throw AiCrossSourceAuthorizationErrors.totalExposesForbiddenDetail();
        }
        if (previouslySeen != null
                && visibleSources != null
                && !previouslySeen.isEmpty()
                // 历史集合里有本次不再覆盖的来源 → 两次合计相减可解
                && !new java.util.LinkedHashSet<>(visibleSources).containsAll(previouslySeen)) {
            throw AiCrossSourceAuthorizationErrors.totalExposesForbiddenDetail();
        }
        if (total == null) {
            throw AiCrossSourceAuthorizationErrors.requestInvalid();
        }
    }

    private static void requireValid(
            List<String> planSources,
            Map<String, CrossSourceAccessFacts> factsByRole,
            Set<CrossSourceCallerRole> callerRoles) {
        requireValidSources(planSources);
        if (factsByRole == null || factsByRole.isEmpty()) {
            throw AiCrossSourceAuthorizationErrors.requestInvalid();
        }
        if (callerRoles == null || callerRoles.isEmpty()) {
            throw AiCrossSourceAuthorizationErrors.roleNotAuthorized();
        }
        for (String role : planSources) {
            if (factsByRole.get(role) == null) {
                // 缺事实 = 无权（fail-closed）：不"按计划自称的有权放行"
                throw AiCrossSourceAuthorizationErrors.sourceNotAuthorized();
            }
        }
    }

    private static void requireValidSources(List<String> planSources) {
        if (planSources == null || planSources.isEmpty()) {
            throw AiCrossSourceAuthorizationErrors.requestInvalid();
        }
        for (String role : planSources) {
            if (role == null || role.isBlank()) {
                throw AiCrossSourceAuthorizationErrors.requestInvalid();
            }
        }
    }

    /** 拒绝时按"是否可反推"选择编号，让调用方知道该申请授权还是该销毁旧产物。 */
    private static RuntimeException denied(Set<String> deniedRoles, Set<String> previouslySeen) {
        Set<String> alsoSeen = intersect(deniedRoles, previouslySeen);
        if (!alsoSeen.isEmpty()) {
            return AiCrossSourceAuthorizationErrors.totalExposesForbiddenDetail();
        }
        return AiCrossSourceAuthorizationErrors.sourceNotAuthorized();
    }

    private static Set<String> intersect(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : left) {
            if (right.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 逐级求交的**可观测**版本：返回每个角色的通过/拒绝结论，供权限矩阵逐格校验。
     *
     * <p>与 {@link #judge} 的区别只在于**不抛异常**：矩阵需要把每一格都渲染出来，
     * 而不是第一格失败就中断。判定口径必须完全一致，因此两者共用同一份
     * {@link CrossSourceAccessFacts#fullyAuthorized()} 判据。
     */
    public List<CrossSourceRoleVerdict> evaluateEach(
            List<String> planSources,
            Map<String, CrossSourceAccessFacts> factsByRole,
            Set<CrossSourceCallerRole> callerRoles) {
        requireValidSources(planSources);
        Map<String, CrossSourceAccessFacts> facts = factsByRole == null ? Map.of() : factsByRole;
        Set<CrossSourceCallerRole> roles = callerRoles == null ? Set.of() : callerRoles;
        Set<String> visibleFields = CrossSourceCallerRole.commonFields(roles);
        List<CrossSourceRoleVerdict> verdicts = new ArrayList<>();
        for (String role : planSources) {
            CrossSourceAccessFacts fact = facts.get(role);
            boolean allowed = fact != null && fact.fullyAuthorized() && !roles.isEmpty();
            String reason = allowed ? null : (fact == null ? "SOURCE_FACTS_MISSING" : fact.denyReason());
            verdicts.add(new CrossSourceRoleVerdict(role, allowed, reason, visibleFields));
        }
        return List.copyOf(verdicts);
    }

    /** 权限矩阵的一格：一个来源角色在一个主体视角下的判定结论。 */
    public record CrossSourceRoleVerdict(String role, boolean allowed, String denyReason, Set<String> visibleFields) {

        public CrossSourceRoleVerdict {
            visibleFields = visibleFields == null ? Set.of() : Set.copyOf(visibleFields);
        }
    }

    /** 判定通过后交给执行层的授权凭据（只含"可以做什么"，不含金额）。 */
    public record CrossSourceGrant(
            List<String> sources, Set<String> visibleFields, Set<CrossSourceCallerRole> callerRoles, boolean allowed) {

        public CrossSourceGrant {
            sources = sources == null ? List.of() : List.copyOf(sources);
            visibleFields = visibleFields == null ? Set.of() : Set.copyOf(visibleFields);
            callerRoles = callerRoles == null ? Set.of() : Set.copyOf(callerRoles);
        }

        /** 该凭据是否允许某字段进入结果（未授权字段一律 false）。 */
        public boolean exposes(String field) {
            return allowed && visibleFields.contains(field);
        }

        /** 角色清单（供矩阵展示；已去重）。 */
        public Map<String, Boolean> asRoleMap() {
            Map<String, Boolean> map = new LinkedHashMap<>();
            for (String source : sources) {
                map.put(source, Boolean.TRUE);
            }
            return map;
        }
    }
}
