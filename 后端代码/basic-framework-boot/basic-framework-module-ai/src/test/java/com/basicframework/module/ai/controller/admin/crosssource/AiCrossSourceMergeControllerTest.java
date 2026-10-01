package com.basicframework.module.ai.controller.admin.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceIntegrity;
import com.basicframework.module.ai.service.authorization.crosssource.CrossSourceResultContract;
import com.basicframework.module.ai.service.query.crosssource.AiCrossSourceMergeService;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceMergeQuery;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Y07 对外入口的控制面契约：权限码与 V97 种子一致、响应恒带口径、只读口径端点不带数字。
 *
 * <p>本类同时是"响应里一定有 {@code crossSourceIntegrity}"这条承诺的<b>序列化级</b>证据：
 * 它把控制器产出的 VO 真正过一遍 Jackson，断言 JSON 文本里有这个键。
 * 只断言 VO 对象上的字段，证不了"线上真的发得出去"——序列化配置（全局 NON_NULL）
 * 完全可能把一个 null 字段从报文里抹掉。
 */
class AiCrossSourceMergeControllerTest {

    private final AiCrossSourceMergeService mergeService = mock(AiCrossSourceMergeService.class);

    private final AiCrossSourceMergeController controller = new AiCrossSourceMergeController(mergeService);

    private static String permissionOf(String methodName) throws Exception {
        Method method = AiCrossSourceMergeController.class.getMethod(
                methodName, String.class, Long.class, String.class, String.class, List.class, List.class);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).as("%s 必须声明服务端权限表达式", methodName).isNotNull();
        return annotation
                .value()
                .replace("@ss.hasPermission(", "")
                .replace(")", "")
                .replace("'", "");
    }

    @Test
    @DisplayName("每个端点恰好一个 @PreAuthorize，且权限码与 V97 菜单种子一致")
    void everyEndpointDeclaresExactlyOnePermissionPolicyMatchingMigrationSeeds() throws Exception {
        int endpoints = 0;
        for (Method method : AiCrossSourceMergeController.class.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null) {
                continue;
            }
            endpoints++;
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("%s 必须且只能声明一个 @PreAuthorize 权限", method.getName())
                    .isNotNull();
            assertThat(annotation.value()).startsWith("@ss.hasPermission('ai:cross-source:");
            assertThat(annotation.value().split("@PreAuthorize").length - 1)
                    .as("%s 不得叠加第二层鉴权口径", method.getName())
                    .isLessThanOrEqualTo(1);
        }
        assertThat(endpoints).as("端点数量与 V97 菜单种子一致").isEqualTo(2);
        assertThat(permissionOf("result")).isEqualTo("ai:cross-source:query");
        assertThat(permissionOf("integrity")).isEqualTo("ai:cross-source:integrity");
    }

    @Test
    @DisplayName("放行结果：响应序列化后 crossSource 标记与完整性口径都在")
    void disclosableResponseAlwaysCarriesMarkerAndIntegrity() throws Exception {
        when(mergeService.merge(any())).thenReturn(disclosable(CrossSourceIntegrity.complete()));

        var vo = controller
                .result("y07-exec-1", 42L, "USER", "y07-alice", List.of("DATA_STEWARD"), List.of())
                .getData();
        String json = JsonUtils.toJsonString(vo);

        assertThat(json).contains("\"crossSource\":true");
        assertThat(json).contains("\"state\":\"COMPLETE\"");
        // 用项目默认的 JsonUtils（全局 NON_NULL）序列化，仍能拿到 "reason":null：
        // 证明 VO 上的 @JsonInclude(ALWAYS) 真的压过了全局默认，
        // 前端"字段存在但值为 null"与"字段缺失"才是两种可区分的情况。
        assertThat(json).contains("\"reason\":null");
        assertThat(vo.getTotalAmount()).isEqualByComparingTo("130.00");
        assertThat(vo.getSourceCount()).isEqualTo(3);
        assertThat(vo.getSources()).hasSize(3);
    }

    @Test
    @DisplayName("不可出具：响应里 crossSource 标记与 WITHHELD 口径都在，且没有任何金额")
    void withheldResponseStillCarriesTheCaliberAndNoNumber() throws Exception {
        when(mergeService.merge(any())).thenReturn(CrossSourceResultContract.withheld("y07-exec-1", "未产出可交付的完整结果"));

        var vo = controller
                .result("y07-exec-1", 42L, "USER", "y07-alice", List.of("DATA_STEWARD"), List.of())
                .getData();
        String json = JsonUtils.toJsonString(vo);

        // fail-closed 的核心：口径恒在，数字恒无
        assertThat(json).contains("\"crossSource\":true");
        assertThat(json).contains("\"state\":\"WITHHELD\"");
        assertThat(vo.getTotalAmount()).isNull();
        assertThat(vo.getSourceCount()).isNull();
        assertThat(vo.getSources()).isEmpty();
    }

    @Test
    @DisplayName("只读口径端点：响应体里只有 state 与 reason，一个金额都没有")
    void integrityEndpointNeverProjectsNumbers() throws Exception {
        when(mergeService.merge(any())).thenReturn(disclosable(CrossSourceIntegrity.complete()));

        var vo = controller
                .integrity("y07-exec-1", 42L, "USER", "y07-alice", List.of("DATA_STEWARD"), List.of())
                .getData();
        String json = JsonUtils.toJsonString(vo);

        assertThat(vo.getState()).isEqualTo(CrossSourceIntegrity.STATE_COMPLETE);
        // 端点级承诺：这条链路上服务端从未把金额序列化出去过
        assertThat(json).doesNotContain("130.00");
        assertThat(json).doesNotContain("orders");
        assertThat(json).doesNotContain("sourceCount");
    }

    @Test
    @DisplayName("角色名认不出来时按空角色集传给服务层（由服务层 fail-closed 拒绝，不退回最低角色）")
    void unknownRoleNamesAreNotSilentlyDowngraded() {
        when(mergeService.merge(any())).thenReturn(disclosable(CrossSourceIntegrity.partial("角色不足")));

        controller.result("y07-exec-1", 42L, "USER", "y07-alice", List.of("SUPER_ADMIN"), List.of());

        var captor = org.mockito.ArgumentCaptor.forClass(CrossSourceMergeQuery.class);
        org.mockito.Mockito.verify(mergeService).merge(captor.capture());
        assertThat(captor.getValue().callerRoles()).isEmpty();
    }

    private static CrossSourceResultContract disclosable(CrossSourceIntegrity integrity) {
        return CrossSourceResultContract.disclosable(
                "y07-exec-1",
                "net_revenue",
                "CNY",
                new BigDecimal("130.00"),
                3,
                List.of(
                        new CrossSourceResultContract.SourceAmount("orders", new BigDecimal("100.00")),
                        new CrossSourceResultContract.SourceAmount("payment", new BigDecimal("30.00")),
                        new CrossSourceResultContract.SourceAmount("invoice", BigDecimal.ZERO)),
                LocalDateTime.of(2026, 3, 1, 10, 0),
                0L,
                true,
                integrity);
    }

    @Test
    @DisplayName("角色参数为 null 时按空角色集传给服务层（防御性分支：不得退回最低角色）")
    void nullRoleListIsNormalisedToEmptyAndRefusedDownstream() {
        when(mergeService.merge(any())).thenReturn(disclosable(CrossSourceIntegrity.partial("角色不足")));

        // @RequestParam 已保证非空，但服务层之外仍可能有非 HTTP 调用方；
        // 这一支必须落到"空角色集 → 服务层 fail-closed 拒绝"，而不是 NPE 或默认放行。
        controller.result("y07-exec-1", 42L, "USER", "y07-alice", null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(CrossSourceMergeQuery.class);
        org.mockito.Mockito.verify(mergeService).merge(captor.capture());
        assertThat(captor.getValue().callerRoles()).isEmpty();
        assertThat(captor.getValue().previouslySeenRoles()).isEmpty();
    }
}
