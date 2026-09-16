package com.basicframework.module.system.service.session;

import cn.hutool.crypto.digest.DigestUtil;
import java.util.Objects;
import java.util.regex.Pattern;

/** 会话令牌摘要工具。 */
final class SessionTokenDigest {

    private static final int REFRESH_PART_LENGTH = 64;
    private static final Pattern REFRESH_TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}(?:[0-9a-f]{64})?");

    private SessionTokenDigest() {}

    static String digest(String token) {
        return DigestUtil.sha256Hex(Objects.requireNonNull(token, "token must not be null"));
    }

    /** 旧令牌整体成为撤销凭据；新令牌的前半段跨刷新保持稳定，只允许撤销，不可单独刷新。 */
    static String refreshFamily(String token) {
        if (token == null || !REFRESH_TOKEN_PATTERN.matcher(token).matches()) {
            return null;
        }
        return token.substring(0, REFRESH_PART_LENGTH);
    }
}
