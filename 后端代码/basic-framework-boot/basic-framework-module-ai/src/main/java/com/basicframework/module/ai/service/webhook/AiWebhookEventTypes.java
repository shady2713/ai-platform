package com.basicframework.module.ai.service.webhook;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Webhook 事件白名单（X10）：只登记**运行终态**三种事件，事件类型与运行状态的关系是固定的推导。
 *
 * <p>为什么只发终态：投递的是"结果"，中间态（ACCEPTED/RUNNING）由订阅方用运行进度/事件流自己读，
 * 把它们也投递出去会让"至少一次投递 + 接收端去重"的语义变复杂，且订阅方拿不到额外事实。
 */
public final class AiWebhookEventTypes {

    /** 事件：运行成功终态。 */
    public static final String RUN_SUCCEEDED = "RUN.SUCCEEDED";

    /** 事件：运行失败终态。 */
    public static final String RUN_FAILED = "RUN.FAILED";

    /** 事件：运行已取消。 */
    public static final String RUN_CANCELLED = "RUN.CANCELLED";

    /** 支持的事件白名单（顺序即文档顺序，解析与展示都按它稳定输出）。 */
    private static final Set<String> SUPPORTED = java.util.Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(RUN_SUCCEEDED, RUN_FAILED, RUN_CANCELLED)));

    private AiWebhookEventTypes() {}

    /** 支持的事件类型（不可变）。 */
    public static Set<String> supported() {
        return SUPPORTED;
    }

    /** 是否在白名单内。 */
    public static boolean isSupported(String eventType) {
        return eventType != null && SUPPORTED.contains(eventType);
    }

    /**
     * 运行状态 → 事件类型；非终态返回 {@code null}（调用方按"不投递"处理）。
     * 推导规则与入队扫描 SQL 的 {@code CONCAT('RUN.', status)} 完全一致。
     */
    public static String ofRunStatus(String runStatus) {
        if (runStatus == null) {
            return null;
        }
        String candidate = "RUN." + runStatus;
        return SUPPORTED.contains(candidate) ? candidate : null;
    }

    /** 把事件白名单序列化为 JSON 数组文本（写入目标行的形式；先去重、按白名单顺序稳定输出）。 */
    public static String serialize(List<String> eventTypes) {
        List<String> normalized = normalize(eventTypes);
        return JsonUtils.toJsonString(normalized);
    }

    /** 解析目标行的事件白名单；非法/空文本返回空列表（按"没有订阅"处理，不抛异常）。 */
    public static List<String> parse(String eventTypesJson) {
        if (eventTypesJson == null || eventTypesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = JsonUtils.parseArray(eventTypesJson, String.class);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (RuntimeException ignored) {
            // 列内容不可解析（历史脏数据/手工改库）：按未订阅处理，绝不放行成"投递一切"
            return List.of();
        }
    }

    /** 收窄：只保留白名单内取值、去重、按白名单顺序输出（不合法取值由调用方在写入前拒绝）。 */
    public static List<String> normalize(List<String> eventTypes) {
        if (eventTypes == null) {
            return List.of();
        }
        Set<String> ordered = new LinkedHashSet<>();
        for (String eventType : SUPPORTED) {
            if (eventTypes.contains(eventType)) {
                ordered.add(eventType);
            }
        }
        return List.copyOf(ordered);
    }
}
