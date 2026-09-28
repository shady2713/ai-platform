package com.basicframework.module.ai.service.webhook;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Webhook 投递正文（X10 协议）：**最小事实**——事件类型、资源引用、运行终态与发生时间。
 *
 * <p>刻意不含：提示词、模型响应正文、会话内容、上游地址、任何凭据与令牌。
 * 订阅方拿到运行引用后按自己的开放通道读取需要的细节（"只发送有权状态与资源引用"）。
 * 字段顺序固定（LinkedHashMap），因此同一事件每次序列化的字节完全一致——
 * 重试复用同一份正文，签名覆盖的摘要也就稳定。
 */
public final class AiWebhookPayload {

    /** 正文协议版本（与文档一致；结构变更时升版本）。 */
    public static final String SCHEMA_VERSION = "1.0";

    /** 时间戳格式（本地时间，秒精度；不含时区偏移，接收端按平台时区解释）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private AiWebhookPayload() {}

    /**
     * 构造规范化正文（字段顺序固定）。
     *
     * @param eventType    事件类型（白名单取值）
     * @param resourceKey  资源业务键（{@code run_} 前缀）
     * @param status       资源状态（运行终态）
     * @param occurredTime 事件发生时间
     */
    public static String build(String eventType, String resourceKey, String status, LocalDateTime occurredTime) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("eventType", eventType);
        payload.put("resourceType", "RUN");
        payload.put("resourceKey", resourceKey);
        payload.put("status", status);
        payload.put("occurredAt", occurredTime == null ? null : TIME_FORMAT.format(occurredTime));
        return JsonUtils.toJsonString(payload);
    }
}
