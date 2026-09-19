package com.basicframework.module.ai.domain.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 受理请求摘要（O02）：幂等判定的唯一依据。
 *
 * <p>摘要**只**由业务内容算出——服务编号、会话编号、消息正文、附件标识与业务上下文：
 * <ul>
 *   <li>排除 traceId、链路追踪与票据 token：重试、换票、换链路都不会被当成新请求；</li>
 *   <li>附件与上下文按规范化顺序拼接：同一组附件与同一份上下文，无论提交顺序都得到同一摘要；</li>
 *   <li>字段间用长度前缀分隔，避免"拼接歧义"（例如 message="ab"+context="c" 与 message="a"+context="bc"）。</li>
 * </ul>
 */
public final class AiRunRequestDigest {

    private AiRunRequestDigest() {}

    /** 计算请求摘要（SHA-256 十六进制）。 */
    public static String compute(
            Long serviceId, Long conversationId, String message, List<String> attachmentKeys, String businessContext) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, serviceId == null ? null : String.valueOf(serviceId));
        append(canonical, conversationId == null ? null : String.valueOf(conversationId));
        append(canonical, message);
        List<String> attachments = new ArrayList<>(attachmentKeys == null ? List.of() : attachmentKeys);
        attachments.sort(String::compareTo);
        append(canonical, String.valueOf(attachments.size()));
        for (String attachment : attachments) {
            append(canonical, attachment);
        }
        append(canonical, businessContext);
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder canonical, String value) {
        String safe = value == null ? "" : value;
        canonical.append(safe.length()).append(':').append(safe).append('|');
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException missingAlgorithm) {
            // JDK 必然提供 SHA-256：缺失属于环境损坏，直接失败而不是降级为无摘要
            throw new IllegalStateException("SHA-256 不可用", missingAlgorithm);
        }
    }
}
