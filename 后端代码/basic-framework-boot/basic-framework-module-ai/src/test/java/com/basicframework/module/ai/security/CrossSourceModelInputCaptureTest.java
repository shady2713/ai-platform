package com.basicframework.module.ai.security;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFacts;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceCallerRole;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceModelInputCaptureVerifier;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceModelInputCaptureVerifier.ModelInputCapture;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 专项三：模型输入捕获无失权数据（Y05）。
 *
 * <p>本类刻意断言"**真的没有**"而不是"再过滤一次"。两者的可观察差别是：
 * 过滤式实现会返回 3/4 条并让调用方知道"有一项被删了"；本卡要求的是
 * <b>整份捕获不可交付</b>，连"有几项失权"都只表现为一次整体拒绝。
 *
 * <p>因此这里的断言全部是"抛错 + 错误码 + 捕获里确实含被禁字面量"，
 * 而不是"结果里没有被禁字面量"——后者对过滤式实现同样成立，钉不住本专项。
 */
@DisplayName("Y05 专项三：模型输入捕获无失权数据")
class CrossSourceModelInputCaptureTest {

    private final CrossSourceModelInputCaptureVerifier verifier = new CrossSourceModelInputCaptureVerifier();

    private static CrossSourceAccessFacts granted(String role) {
        return new CrossSourceAccessFacts(role, "sys-" + role, "y04_" + role, true, true, true);
    }

    private static ModelInputCapture capture() {
        return new ModelInputCapture(
                "cap-y05-001",
                List.of("order", "invoice", "payment"),
                Set.of("amount", "currency", "customer_key"),
                "订单 100.00 + 发票 25.00 + 回款 5.00 = 130.00");
    }

    // ---------- 正向 ----------

    @Test
    @DisplayName("正向：捕获内全部来源当前仍有权时，完整交付")
    void aFullyAuthorizedCaptureIsDeliveredInFull() {
        Map<String, CrossSourceAccessFacts> current =
                Map.of("order", granted("order"), "invoice", granted("invoice"), "payment", granted("payment"));

        var delivered = verifier.requireStillAuthorized(capture(), current);

        assertThat(delivered.sourceRoles()).containsExactly("order", "invoice", "payment");
        assertThat(verifier.lostRoles(capture(), current)).isEmpty();
    }

    @Test
    @DisplayName("正向：角色未降级时捕获可送进模型")
    void aCaptureIsPromotableWhenTheRoleStillSeesEveryFieldInIt() {
        Map<String, CrossSourceAccessFacts> current =
                Map.of("order", granted("order"), "invoice", granted("invoice"), "payment", granted("payment"));

        var promotable = verifier.requirePromotableToModel(capture(), current, Set.of(CrossSourceCallerRole.ANALYST));

        assertThat(promotable.captureId()).isEqualTo("cap-y05-001");
    }

    // ---------- 反向：失权后捕获里不得残留旧数据 ----------

    @Test
    @DisplayName("反向：失权后整份捕获被拒，且不得返回任何部分结果")
    void afterRevocationTheWholeCaptureIsRefusedNotFiltered() {
        Map<String, CrossSourceAccessFacts> current = Map.of(
                "order", granted("order"),
                "invoice", granted("invoice"),
                // payment 已失权：调用方现在对它没有映射权限
                "payment", new CrossSourceAccessFacts("payment", "sys-payment", "y04_payments", true, true, false));

        assertThatThrownBy(() -> verifier.requireStillAuthorized(capture(), current))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED.getCode());

        // 关键断言：拒绝时**一个来源都不交付**。
        // 过滤式实现在这里会返回 order+invoice 两条；本卡要求返回零条。
        assertThatThrownBy(() -> verifier.requireStillAuthorized(capture(), current))
                .isInstanceOf(ServiceException.class)
                // 消息是静态的：连捕获编号都不回显（编号是调用方自己给的东西，回显它没有价值，
                // 而一旦消息能带动态内容，"塞什么进去"就会变成新的信息出口）
                .hasMessage(AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED.getMsg())
                .hasMessageNotContaining("cap-y05-001");
    }

    @Test
    @DisplayName("反向：捕获正文里确实含有被禁来源的字面量，验证的是它没被交出去而不是它不存在")
    void theCaptureReallyContainsTheForbiddenTextAndIsStillNotDelivered() {
        ModelInputCapture original = capture();
        // 证明"被禁数据真的在捕获里"：否则"拒绝交付"可能只是因为捕获本来就是空的
        assertThat(original.containsText("回款")).isTrue();
        assertThat(original.containsText("5.00")).isTrue();

        Map<String, CrossSourceAccessFacts> current = Map.of(
                "order", granted("order"),
                "invoice", granted("invoice"),
                "payment", new CrossSourceAccessFacts("payment", "sys-payment", "y04_payments", true, true, false));

        assertThatThrownBy(() -> verifier.requireStillAuthorized(original, current))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("反向：失权诊断能指出确切角色，但诊断不进入交付路径")
    void lostRolesNamesTheExactRoleForDiagnostics() {
        Map<String, CrossSourceAccessFacts> current = Map.of(
                "order", granted("order"),
                "invoice", granted("invoice"),
                "payment", new CrossSourceAccessFacts("payment", "sys-payment", "y04_payments", true, true, false));

        // 诊断口径精确到角色（运维要知道该恢复哪一级授权）
        assertThat(verifier.lostRoles(capture(), current)).containsExactly("payment");
    }

    @Test
    @DisplayName("反向：授权事实缺失按失权处理（查不到授权不等于仍然有权）")
    void missingAuthorizationFactsAreTreatedAsLoss() {
        // 只给两个来源的事实，第三个查不到
        Map<String, CrossSourceAccessFacts> partial = Map.of("order", granted("order"), "invoice", granted("invoice"));

        assertThatThrownBy(() -> verifier.requireStillAuthorized(capture(), partial))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED.getCode());

        // 事实集合整体为 null 同样拒绝
        assertThatThrownBy(() -> verifier.requireStillAuthorized(capture(), null))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("反向：角色降级后捕获里的越权字段不得再送进模型")
    void aDowngradedRoleCannotPromoteACaptureContainingFieldsItNoLongerSees() {
        Map<String, CrossSourceAccessFacts> current =
                Map.of("order", granted("order"), "invoice", granted("invoice"), "payment", granted("payment"));

        // 捕获里含 customer_key（ANALYST 可见），但角色已降级为 AGGREGATE_READER（不可见）
        assertThatThrownBy(() -> verifier.requirePromotableToModel(
                        capture(), current, Set.of(CrossSourceCallerRole.AGGREGATE_READER)))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED.getCode());
    }

    // ---------- 反向：空捕获 fail-closed ----------

    @Test
    @DisplayName("反向：空捕获与 null 捕获一律拒绝，不返回空结果冒充成功")
    void emptyOrNullCapturesAreRefused() {
        Map<String, CrossSourceAccessFacts> current = Map.of("order", granted("order"));

        assertThatThrownBy(() -> verifier.requireStillAuthorized(null, current))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED.getCode());

        ModelInputCapture empty = new ModelInputCapture("cap-empty", List.of(), Set.of(), "");
        assertThatThrownBy(() -> verifier.requireStillAuthorized(empty, current))
                .isInstanceOf(ServiceException.class);
        // null 捕获的失权诊断不抛错（供调用方先判空），但要求交付时仍被拒
        assertThat(verifier.lostRoles(null, current)).isEmpty();
    }

    @Test
    @DisplayName("捕获 record 的防御性归零：null 集合归一为空而不是保留 null")
    void theCaptureRecordNormalizesNullCollections() {
        ModelInputCapture normalized = new ModelInputCapture("cap-null", null, null, null);

        assertThat(normalized.sourceRoles()).isEmpty();
        assertThat(normalized.visibleFields()).isEmpty();
        // null 正文不触发 NPE，且不"匹配"任何字面量
        assertThat(normalized.containsText("anything")).isFalse();
        assertThat(normalized.containsText(null)).isFalse();
        assertThat(normalized.containsText("")).isFalse();
    }
}
