package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 缺失来源角色的编码（Y04）。
 *
 * <p>"没有回款记录"与"回款金额是 0"必须能区分，因此空缺编码为 {@code []} 而不是空串，
 * 脏角色名被丢弃而不是写进执行记录。
 */
class CrossSourceRolesTest {

    @Test
    void encodesRolesAsAJsonArray() {
        assertThat(CrossSourceRoles.encode(List.of("invoice", "payment"))).isEqualTo("[\"invoice\",\"payment\"]");
    }

    @Test
    void encodesAnEmptyListAsAnEmptyArray() {
        // 空数组而不是空串：空串与"没有编码"无法区分，缺口就会在几跳之后丢失
        assertThat(CrossSourceRoles.encode(List.of())).isEqualTo("[]");
        assertThat(CrossSourceRoles.encode(null)).isEqualTo("[]");
    }

    @Test
    void dropsUnusableRoleNamesInsteadOfWritingArbitraryText() {
        String encoded =
                CrossSourceRoles.encode(java.util.Arrays.asList("order", null, "  ", "x".repeat(65), "invoice"));

        // 只保留形态良好的角色名：编不进去的名字说明上游传了脏数据
        assertThat(encoded).isEqualTo("[\"order\",\"invoice\"]");
    }

    @Test
    void capsTheNumberOfEncodedRoles() {
        List<String> many = new java.util.ArrayList<>();
        for (int index = 0; index < 40; index++) {
            many.add("role_" + index);
        }

        String encoded = CrossSourceRoles.encode(many);

        // 16 个角色：截断生效，超过上限的部分不进执行记录（列宽 512 也不该被撑爆）
        assertThat(encoded).isEqualTo(CrossSourceRoles.encode(many.subList(0, CrossSourceRoles.MAX_ROLES)));
        assertThat(encoded.split("\"role_").length - 1).isEqualTo(CrossSourceRoles.MAX_ROLES);
    }
}
