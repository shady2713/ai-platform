package com.basicframework.module.ai.domain.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 应用客户端秘密的生成与摘要（A01）。
 *
 * <p>秘密明文只在创建/轮换的响应里出现一次：平台只保存 SHA-256 摘要，
 * 校验用常量时间比较，摘要与明文都不进日志与对象 toString。
 */
public final class ApplicationSecrets {

    /** 秘密前缀：便于运维识别泄漏源，前缀本身不含任何强度信息。 */
    public static final String PREFIX = "aiapp_";

    private static final int SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApplicationSecrets() {}

    /** 生成新秘密明文（256 位随机，Base64 URL 安全无填充）。 */
    public static String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 计算摘要（十六进制小写）。 */
    public static String digest(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte value : hashed) {
                builder.append(Character.forDigit((value >> 4) & 0xF, 16));
                builder.append(Character.forDigit(value & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 必备算法，缺失即环境损坏
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 常量时间比较摘要，避免通过响应时间区分"前缀正确"与"完全错误"。 */
    public static boolean matches(String expectedDigest, String presentedSecret) {
        if (expectedDigest == null || presentedSecret == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expectedDigest.getBytes(StandardCharsets.US_ASCII),
                digest(presentedSecret).getBytes(StandardCharsets.US_ASCII));
    }
}
