package com.basicframework.module.ai.service.webhook;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_DELIVERY_EXHAUSTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_TARGET_DISABLED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_WEBHOOK_TARGET_NOT_FOUND;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * Webhook 投递的稳定失败词表（X10）：投递行与尝试行只落这里的取值，绝不落上游报文、地址与凭据。
 *
 * <p>两类取值：
 * <ol>
 *   <li><b>平台错误码</b>：有对应 {@link ErrorCode} 的失败按平台码的规范写法落库
 *       （与 {@code docs/contracts/ai/error-code-map.md} 一致，形如 {@code 1_003_011_001}），
 *       管理端响应里出现的也是同一个码；</li>
 *   <li><b>传输原因码</b>：受控出站边界与接收端状态机产生的确定原因（kebab 小写），
 *       没有对应平台码，但取值集合是封闭的（本类即词表）。</li>
 * </ol>
 *
 * <p>不变量：{@code failure_code} 只在终态写入，重试中的投递只有 {@code last_error_code}；
 * 「重试预算耗尽」是一个独立结论（{@link #DELIVERY_EXHAUSTED}），不冒充底层原因。
 */
public final class AiWebhookFailureCodes {

    /** 目标已停用或已删除：入队与发送前都拒绝（无网络请求）。 */
    public static final String TARGET_DISABLED = platform(AI_WEBHOOK_TARGET_DISABLED);

    /** 目标不存在（入队后目标被删除）。 */
    public static final String TARGET_NOT_FOUND = platform(AI_WEBHOOK_TARGET_NOT_FOUND);

    /** 重试预算耗尽（有界重试的终态；底层原因保留在 last_error_code）。 */
    public static final String DELIVERY_EXHAUSTED = platform(AI_WEBHOOK_DELIVERY_EXHAUSTED);

    /** 确定失败：目标不在受控出站的允许清单内（零请求）。 */
    public static final String TARGET_NOT_ALLOWED = "target-not-allowed";

    /** 确定失败：目标解析到私网/环回地址且未被显式批准（零请求）。 */
    public static final String PRIVATE_TARGET_DENIED = "private-target-denied";

    /** 确定失败：出站请求自身不合规（方法/地址/头部超限），零请求或请求未被接受。 */
    public static final String REQUEST_INVALID = "request-invalid";

    /** 确定失败：响应体超过上限（按"对端行为不可信"处理，不重试）。 */
    public static final String RESPONSE_TOO_LARGE = "response-too-large";

    /** 确定失败：接收端返回 3xx；受控出站不跟随重定向，改址由人工重新登记目标。 */
    public static final String REDIRECT_NOT_FOLLOWED = "redirect-not-followed";

    /** 确定失败：接收端 4xx（接收端明确拒绝，重试同一请求没有意义）。 */
    public static final String HTTP_CLIENT_ERROR = "http-client-error";

    /** 可重试：接收端 5xx。 */
    public static final String HTTP_SERVER_ERROR = "http-server-error";

    /** 可重试：接收端 429/限流。 */
    public static final String HTTP_RATE_LIMITED = "http-rate-limited";

    /** 可重试：连接或读取超时。 */
    public static final String TIMEOUT = "timeout";

    /** 可重试：连接失败（DNS、拒绝连接、读中断）。 */
    public static final String CONNECT_FAILED = "connect-failed";

    /** 可重试：投递器内部异常（有界重试收敛，不假装成功）。 */
    public static final String INTERNAL_ERROR = "internal-error";

    /** 确定失败：目标签名密钥不可用（未配置或解密失败），不发请求。 */
    public static final String SIGNING_KEY_UNAVAILABLE = "signing-key-unavailable";

    private AiWebhookFailureCodes() {}

    /** 平台错误码的规范写法（{@code 1003011001} → {@code 1_003_011_001}），与错误码目录同一形状。 */
    static String platform(ErrorCode errorCode) {
        String digits = String.valueOf(errorCode.getCode());
        if (digits.length() != 10) {
            return digits;
        }
        return digits.substring(0, 1) + "_" + digits.substring(1, 4) + "_" + digits.substring(4, 7) + "_"
                + digits.substring(7);
    }
}
