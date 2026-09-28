package com.basicframework.module.ai.service.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 投递签名（X10 协议）：HMAC-SHA256，签名覆盖「时间戳 + 投递编号 + 正文摘要」，防篡改与防重放。
 *
 * <p>协议（与 {@code docs/contracts/ai/webhook-protocol.md} 一致，接收端按同一算法校验）：
 * <pre>
 *   canonical = timestamp + "." + deliveryNo + "." + sha256hex(body)
 *   signature = "v1=" + hex(hmacSha256(secret, canonical))
 * </pre>
 * 接收端必须同时做三件事才认为投递有效：<b>验签</b>（常量时间比较）、<b>时间戳在容忍窗口内</b>
 * （默认 ±5 分钟）、<b>投递编号未见过</b>（去重表；重试沿用同一编号，因此重复投递天然被拒）。
 *
 * <p>为什么摘要进签名而不是每次都签正文：正文在入队时冻结、重试复用同一份字节，
 * 签摘要与签正文是同一件事，但摘要让接收端可以先按内容/编号做去重再验签。
 * 密钥只从目标行解密得到，不进日志、不进响应、不参与任何 toString。
 */
public final class AiWebhookSignature {

    /** 请求头：投递编号（重试不变；接收端据此去重）。 */
    public static final String HEADER_DELIVERY_NO = "X-AI-Webhook-Id";

    /** 请求头：签名时间戳（epoch 秒）。 */
    public static final String HEADER_TIMESTAMP = "X-AI-Webhook-Timestamp";

    /** 请求头：事件类型。 */
    public static final String HEADER_EVENT = "X-AI-Webhook-Event";

    /** 请求头：第几次尝试（从 1 开始；仅用于观测，不参与签名）。 */
    public static final String HEADER_ATTEMPT = "X-AI-Webhook-Attempt";

    /** 请求头：资源引用（{@code RUN:<runKey>}），接收端可不解析正文先做路由。 */
    public static final String HEADER_RESOURCE = "X-AI-Webhook-Resource";

    /** 请求头：签名。 */
    public static final String HEADER_SIGNATURE = "X-AI-Webhook-Signature";

    /** 协议版本前缀（算法变更时升版本，接收端按前缀选择校验方式）。 */
    public static final String SCHEME = "v1";

    /** 签名算法。 */
    public static final String ALGORITHM = "HmacSHA256";

    /** 时间戳容忍窗口（接收端判定"过期/未来时间戳"的默认阈值）。 */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    /** 签名密钥的最小长度（更短的密钥按不合规拒绝，写入前校验）。 */
    public static final int MIN_SECRET_LENGTH = 16;

    /** 签名密钥的最大长度（与列宽一致，避免截断）。 */
    public static final int MAX_SECRET_LENGTH = 128;

    private AiWebhookSignature() {}

    /** 待签名规范串：{@code timestamp.deliveryNo.sha256hex(body)}。 */
    public static String canonical(String timestamp, String deliveryNo, byte[] body) {
        return timestamp + "." + deliveryNo + "." + sha256Hex(body);
    }

    /** 计算签名头取值（含 {@code v1=} 前缀）。 */
    public static String sign(String secret, String timestamp, String deliveryNo, byte[] body) {
        return SCHEME + "=" + hmacHex(secret, canonical(timestamp, deliveryNo, body));
    }

    /**
     * 校验签名（接收端样例与平台自测使用）：常量时间比较，前缀不符、长度不符一律为假。
     *
     * @param secret    签名密钥（与平台侧同一份）
     * @param timestamp 请求头里的时间戳（原样字符串，不做二次格式化）
     * @param deliveryNo 请求头里的投递编号
     * @param body      收到的原始请求体字节（不得重新序列化）
     * @param signature 请求头里的签名（{@code v1=<hex>}）
     */
    public static boolean verify(String secret, String timestamp, String deliveryNo, byte[] body, String signature) {
        if (secret == null || timestamp == null || deliveryNo == null || signature == null) {
            return false;
        }
        String expected = sign(secret, timestamp, deliveryNo, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    /** 时间戳是否在容忍窗口内（时钟偏移双向限制；越界即按重放/过期拒绝）。 */
    public static boolean withinTolerance(long timestampEpochSeconds, long nowEpochSeconds, Duration tolerance) {
        Duration effective = tolerance == null ? DEFAULT_TOLERANCE : tolerance;
        long delta = Math.abs(nowEpochSeconds - timestampEpochSeconds);
        return delta <= effective.toSeconds();
    }

    /** 正文摘要（十六进制小写；与投递行的 payload_digest 同一算法）。 */
    public static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 密钥长度是否合规（写入/轮换前校验）。 */
    public static boolean isAcceptableSecret(String secret) {
        return secret != null && secret.length() >= MIN_SECRET_LENGTH && secret.length() <= MAX_SECRET_LENGTH;
    }

    private static String hmacHex(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC 计算失败", exception);
        }
    }
}
