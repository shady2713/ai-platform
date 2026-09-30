package com.basicframework.module.ai.service.authorization.crosssource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型输入捕获的授权复核（Y05 专项三）：**读取时**确认捕获里没有已失权的数据。
 *
 * <p>本类的定位刻意是"验证真的没有"，而不是"再过滤一次"。两者的区别决定了实现：
 * <ul>
 *   <li><b>再过滤一次</b>：把捕获里的失权项删掉再返回。调用方仍然能从"被删了几项"
 *       以及剩余项的规模推断出被删项是什么——过滤动作本身就是信道；</li>
 *   <li><b>验证真的没有</b>：把捕获里的每个来源角色拿去向<b>当前</b>授权事实核对，
 *       只要有一项失权，<b>整份捕获都不返回</b>。调用方拿不到任何残余，
 *       连"有几项被拦"都只表现为一次整体拒绝。</li>
 * </ul>
 *
 * <p>因此本类<b>不产出部分结果</b>：它要么返回完整捕获（全部来源当前仍然有权），
 * 要么抛 {@code AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED}。
 * "返回 3/4 条"这种设计在本专项里是被禁止的——那正是过滤式实现会做的事。
 *
 * <p>复核用<b>当前</b>事实而不是写入时的结论：捕获可能是几分钟前生成的，
 * 主体在那之后失权了。写入时判定"有权"对读取毫无意义。
 */
public final class CrossSourceModelInputCaptureVerifier {

    /**
     * 复核一份捕获是否可以交付。
     *
     * @param capture        待交付的捕获（含各来源角色与其授权事实指纹）
     * @param currentFacts   当前授权事实（按来源角色）
     * @return 可交付的捕获（与入参同一实例，不做裁剪）
     * @throws com.basicframework.framework.common.exception.ServiceException 任一来源已失权
     */
    public ModelInputCapture requireStillAuthorized(
            ModelInputCapture capture, java.util.Map<String, CrossSourceAccessFacts> currentFacts) {
        if (capture == null || capture.sourceRoles().isEmpty()) {
            throw AiCrossSourceAuthorizationErrors.captureNotAuthorized();
        }
        List<String> lostRoles = lostRoles(capture, currentFacts);
        if (!lostRoles.isEmpty()) {
            // 整份拒绝：不做部分交付，避免"删了几项"本身泄露失权范围
            throw AiCrossSourceAuthorizationErrors.captureNotAuthorized();
        }
        return capture;
    }

    /**
     * 捕获里已经失权的来源角色（诊断用；不进任何对外响应）。
     *
     * <p>与 {@link #requireStillAuthorized} 共用同一份判据，两者不会给出不同结论。
     */
    public List<String> lostRoles(
            ModelInputCapture capture, java.util.Map<String, CrossSourceAccessFacts> currentFacts) {
        List<String> lost = new ArrayList<>();
        if (capture == null) {
            return lost;
        }
        java.util.Map<String, CrossSourceAccessFacts> facts = currentFacts == null ? java.util.Map.of() : currentFacts;
        for (String role : capture.sourceRoles()) {
            CrossSourceAccessFacts fact = facts.get(role);
            // 事实缺失按失权处理（fail-closed）：查不到授权 ≠ 仍然有权
            if (fact == null || !fact.fullyAuthorized()) {
                lost.add(role);
            }
        }
        return List.copyOf(lost);
    }

    /**
     * 捕获是否可以送进模型（完整性 UI 的展示前提）。
     *
     * <p>与读取复核同源，但额外要求：捕获声明的可见字段必须仍在当前角色的可见字段内。
     * 角色被降级（例如从 DATA_STEWARD 降到 AGGREGATE_READER）时，
     * 捕获里的 {@code customer_key} 就不再允许出现在模型输入里。
     */
    public ModelInputCapture requirePromotableToModel(
            ModelInputCapture capture,
            java.util.Map<String, CrossSourceAccessFacts> currentFacts,
            Set<CrossSourceCallerRole> currentRoles) {
        requireStillAuthorized(capture, currentFacts);
        Set<String> visible = CrossSourceCallerRole.commonFields(currentRoles);
        Set<String> disallowed = new LinkedHashSet<>(capture.visibleFields());
        disallowed.removeAll(visible);
        if (!disallowed.isEmpty()) {
            throw AiCrossSourceAuthorizationErrors.captureNotAuthorized();
        }
        return capture;
    }

    /**
     * 模型输入捕获：跨源结果送进模型/完整性 UI 时的**内容快照**。
     *
     * <p>{@code visibleFields} 记录的是捕获生成时**实际进模型**的字段集合，
     * 而不是"理论上可见"的集合——后者会让人误以为降级后仍可复用同一份捕获。
     *
     * @param captureId     捕获标识
     * @param sourceRoles   参与本次合并的来源角色（失权判定的对象）
     * @param visibleFields 捕获中实际包含的字段
     * @param renderedText  送进模型的正文（仅用于断言不含被禁字面量，不参与判定）
     */
    public record ModelInputCapture(
            String captureId, List<String> sourceRoles, Set<String> visibleFields, String renderedText) {

        public ModelInputCapture {
            sourceRoles = sourceRoles == null ? List.of() : List.copyOf(sourceRoles);
            visibleFields = visibleFields == null ? Set.of() : Set.copyOf(visibleFields);
        }

        /** 捕获是否包含某个字面量（测试与审计用；判定本身不依赖正文内容）。 */
        public boolean containsText(String needle) {
            return needle != null && !needle.isEmpty() && renderedText != null && renderedText.contains(needle);
        }
    }
}
