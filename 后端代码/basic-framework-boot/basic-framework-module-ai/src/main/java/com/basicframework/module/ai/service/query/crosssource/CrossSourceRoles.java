package com.basicframework.module.ai.service.query.crosssource;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * 缺失来源角色的编码（Y04）：执行记录与结果里都要留一份"缺了谁"。
 *
 * <p>"没有回款记录"与"回款金额是 0"在报表上是两件完全不同的事。不把它们编码进执行记录，
 * 跨源结果就只剩下一个合计数字，缺口信息在几跳之后丢失——而缺口恰恰是跨源聚合
 * 最需要被追问的地方（与 Y03 的 {@code AI_METRIC_GAP_CLARIFICATION_REQUIRED} 同一立场）。
 *
 * <p>只编角色名，不编任何来源数据值。
 */
final class CrossSourceRoles {

    /** 缺失角色清单的最大长度（与执行记录列宽一致；超出即视为不可信输入）。 */
    static final int MAX_ROLES = 16;

    /** 角色名的最大长度（与来源角色列宽一致）。 */
    private static final int MAX_ROLE_LENGTH = 64;

    private CrossSourceRoles() {}

    /** 缺失角色列表 → JSON 数组文本（空列表编码为 {@code []} 而不是空串）。 */
    static String encode(List<String> roles) {
        List<String> safe = new ArrayList<>();
        if (roles != null) {
            for (String role : roles) {
                if (role == null || role.isBlank() || role.length() > MAX_ROLE_LENGTH) {
                    // 编不进去的角色名说明上游传了脏数据：宁可丢它也不能把任意文本写进记录
                    continue;
                }
                safe.add(role);
            }
        }
        // 先判后加：先 add 再判会让上限多放行一个角色，执行记录里的缺口数与实际不符
        if (safe.size() > MAX_ROLES) {
            return JsonUtils.toJsonString(new ArrayList<>(safe.subList(0, MAX_ROLES)));
        }
        return JsonUtils.toJsonString(safe);
    }
}
