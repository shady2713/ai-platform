package com.basicframework.module.ai.service.realtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 短期会话票据（X05）：生成 32 字节随机票据，库中只存 SHA-256 摘要。
 *
 * <p>为什么票据与 MEMBER 会话分开：媒体面（重）建立可能发生在 MEMBER 会话之外（事件通道、媒体网关），
 * 票据把"建立会话媒体面"的权限压缩到一次、一段有限时间、一个具体会话上；明文只在受理/续票响应
 * 出现一次，库泄露不等于凭据泄露。摘要本身就是凭据定位串，因此同样不进日志与 toString。
 *
 * <p>会话业务键（{@code sessionKey}）不是凭据：它用于适配器侧幂等与排障，可以出现在日志里。
 */
@Component
public class AiRealtimeSessionTickets {

    /** 平台会话业务键前缀（稳定形状，便于排障与日志检索）。 */
    private static final String SESSION_KEY_PREFIX = "rts_";

    /** 票据随机字节数（32 字节 → Base64URL 无填充 43 字符）。 */
    private static final int TICKET_BYTES = 32;

    /** 会话业务键随机字节数。 */
    private static final int SESSION_KEY_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    /** 新票据明文（Base64URL 无填充）。 */
    public String newTicket() {
        byte[] bytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 新会话业务键（不是凭据，可进日志）。 */
    public String newSessionKey() {
        byte[] bytes = new byte[SESSION_KEY_BYTES];
        secureRandom.nextBytes(bytes);
        return SESSION_KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 票据摘要（小写十六进制 SHA-256）；空输入返回空。 */
    public Optional<String> digest(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Optional.of(
                    HexFormat.of().formatHex(digest.digest(ticket.trim().getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /** 常量时间比较摘要，避免比较耗时泄露前缀信息。 */
    public boolean digestMatches(String expectedDigest, String presentedTicket) {
        String presented = digest(presentedTicket).orElse(null);
        if (expectedDigest == null || presented == null || expectedDigest.length() != presented.length()) {
            return false;
        }
        int diff = 0;
        for (int index = 0; index < expectedDigest.length(); index++) {
            diff |= expectedDigest.charAt(index) ^ presented.charAt(index);
        }
        return diff == 0;
    }
}
