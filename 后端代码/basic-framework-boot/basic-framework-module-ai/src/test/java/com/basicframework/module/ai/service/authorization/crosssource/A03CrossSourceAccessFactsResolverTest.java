package com.basicframework.module.ai.service.authorization.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.domain.policy.AiAction;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import com.basicframework.module.ai.service.authorization.AiAuthorizationService;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFactsResolver.CrossSourceFactsQuery;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceAccessFactsResolver.SourceBinding;
import com.basicframework.module.ai.service.authorization.dto.AiAuthorizationDecisionDTO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Y07 授权事实解析：把当前真实授权（A03）翻译成 Y05 的三级事实。
 *
 * <p>钉三件事：数据集与映射是<b>两次独立</b>判定（压缩成一次会让"只读数据不做关联"
 * 绕过映射闸门）；查不到授权按<b>无权</b>处理而不是省略角色（省略会让
 * {@code judge} 报"入参不合法"而不是"无权"，两种拒绝的处置动作不同）；
 * 资源键为空白直接判无权，不去问授权。
 */
class A03CrossSourceAccessFactsResolverTest {

    private final AiAuthorizationService authorizationService = mock(AiAuthorizationService.class);

    private final A03CrossSourceAccessFactsResolver resolver =
            new A03CrossSourceAccessFactsResolver(authorizationService);

    private static AiAuthorizationDecisionDTO decision(boolean allowed) {
        return new AiAuthorizationDecisionDTO().setAllowed(allowed);
    }

    /**
     * 默认一律无权，再逐个资源键开授权。
     *
     * <p>先铺宽匹配再叠精确匹配：Mockito 取<b>最后一条</b>匹配到的桩，
     * 因此顺序反了会让"只授权数据集"这类用例意外把映射也放行。
     */
    private void granted(String resourceKey) {
        if (!defaultDenied) {
            when(authorizationService.authorize(anyLong(), anyString(), anyString(), any(), any(), any(), anyList()))
                    .thenReturn(decision(false));
            defaultDenied = true;
        }
        when(authorizationService.authorize(
                        anyLong(), anyString(), anyString(), any(), eq(resourceKey), any(), anyList()))
                .thenReturn(decision(true));
    }

    /** 允许某些用例直接问"什么都没授权"（不铺默认桩，靠 null 之外的显式桩）。 */
    private void deniedByDefault() {
        when(authorizationService.authorize(anyLong(), anyString(), anyString(), any(), any(), any(), anyList()))
                .thenReturn(decision(false));
        defaultDenied = true;
    }

    private boolean defaultDenied;

    private CrossSourceFactsQuery query(List<SourceBinding> bindings) {
        return new CrossSourceFactsQuery(42L, "USER", "y07-alice", bindings);
    }

    @Test
    @DisplayName("数据集与映射都授权时，事实三级全真")
    void datasetAndMappingBothGrantedYieldsFullyAuthorizedFacts() {
        granted("y04_orders");
        granted("mapping:y04_orders");

        Map<String, CrossSourceAccessFacts> facts =
                resolver.resolve(query(List.of(new SourceBinding("orders", "y04_orders"))));

        CrossSourceAccessFacts orders = facts.get("orders");
        assertThat(orders).isNotNull();
        assertThat(orders.sourceReadable()).isTrue();
        assertThat(orders.mappingAuthorized()).isTrue();
        assertThat(orders.fullyAuthorized()).isTrue();
        assertThat(orders.denyReason()).isNull();
        // 角色名即稳定键
        assertThat(orders.key()).isEqualTo("orders");
    }

    @Test
    @DisplayName("只授权数据集、不授权映射时：数据可读但关联被拒（专项二的独立维度）")
    void mappingIsDeniedIndependentlyOfDataset() {
        granted("y04_orders");

        Map<String, CrossSourceAccessFacts> facts =
                resolver.resolve(query(List.of(new SourceBinding("orders", "y04_orders"))));

        CrossSourceAccessFacts orders = facts.get("orders");
        assertThat(orders.sourceReadable()).isTrue();
        assertThat(orders.mappingAuthorized()).isFalse();
        assertThat(orders.fullyAuthorized()).isFalse();
        assertThat(orders.denyReason()).isEqualTo("ENTITY_MAPPING_NOT_AUTHORIZED");
    }

    @Test
    @DisplayName("查不到授权按无权处理：角色不省略、事实显式为 false（fail-closed）")
    void missingGrantIsTreatedAsUnauthorizedRatherThanOmitted() {
        // 一个授权都不给：Y05 的 judge 必须能报"无权"，而不是因为 facts 缺角色而报"入参不合法"
        deniedByDefault();
        Map<String, CrossSourceAccessFacts> facts =
                resolver.resolve(query(List.of(new SourceBinding("orders", "y04_orders"))));

        assertThat(facts).containsOnlyKeys("orders");
        CrossSourceAccessFacts orders = facts.get("orders");
        assertThat(orders.sourceReadable()).isFalse();
        // 没有任何授权时系统级与数据集级同时为假，denyReason 按系统→数据集→映射的顺序报第一级
        assertThat(orders.denyReason()).isEqualTo("SOURCE_SYSTEM_NOT_AUTHORIZED");
    }

    @Test
    @DisplayName("数据编号为空白时直接判无权，不去问授权（查不到不等于仍然有权）")
    void blankDatasetKeyIsDeniedWithoutAskingAuthorization() {
        deniedByDefault();
        Map<String, CrossSourceAccessFacts> facts = resolver.resolve(query(List.of(new SourceBinding("orders", "  "))));

        CrossSourceAccessFacts orders = facts.get("orders");
        assertThat(orders.sourceReadable()).isFalse();
        assertThat(orders.fullyAuthorized()).isFalse();
    }

    @Test
    @DisplayName("角色名为空时稳定键退化为 系统/数据集 组合（避免不同来源撞成同一个键）")
    void blankRoleFallsBackToSystemAndDatasetKey() {
        granted("y04_orders");
        granted("mapping:y04_orders");

        Map<String, CrossSourceAccessFacts> facts =
                resolver.resolve(query(List.of(new SourceBinding(null, "y04_orders"))));

        // 键退化为 "数据集编号/数据集编号"（systemCode 与 datasetCode 同值）
        assertThat(facts).containsOnlyKeys("y04_orders/y04_orders");
    }

    @Test
    @DisplayName("绑定列表为 null 时按空列表处理（不抛 NullPointerException）")
    void nullBindingsAreNormalisedToEmpty() {
        assertThat(new CrossSourceFactsQuery(42L, "USER", "y07-alice", null).bindings())
                .isEmpty();
        assertThat(resolver.resolve(query(null))).isEmpty();
    }

    @Test
    @DisplayName("读取动作固定为 READ，资源类型固定为 DATASET（不自造第二套资源类型）")
    void authorizationAlwaysUsesReadOnDatasetResource() {
        granted("y04_orders");
        resolver.resolve(query(List.of(new SourceBinding("orders", "y04_orders"))));

        org.mockito.Mockito.verify(authorizationService)
                .authorize(
                        eq(42L),
                        eq("USER"),
                        eq("y07-alice"),
                        eq(AiResourceType.DATASET),
                        eq("y04_orders"),
                        eq(AiAction.READ),
                        eq(List.of("y04_orders")));
    }
}
