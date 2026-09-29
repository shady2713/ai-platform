package com.basicframework.module.ai.service.report.share;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 分享令牌的生成与摘要（X11）：生成与校验共用同一口径，杜绝"两套编码"漂移。
 *
 * <p>口径：明文令牌 = 32 字节 {@link SecureRandom} → Base64URL（无填充，43 字符）；
 * 摘要 = 明文 UTF-8 字节的 SHA-256 小写十六进制（64 字符，即库里的 {@code token_hash}）。
 * 明文令牌只在创建响应返回一次，本类不落库、不打日志。
 */
public final class AiReportShareTokens {

    /** 明文令牌字节数（256 位熵：枚举与碰撞都不可行）。 */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private AiReportShareTokens() {}

    /** 生成明文令牌（Base64URL，无填充）。调用方负责只把它返回一次。 */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 明文令牌的 SHA-256 摘要（小写十六进制，64 字符）。 */
    public static String digest(String token) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
