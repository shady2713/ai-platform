package com.basicframework.module.ai.service.realtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 实时会话参数守卫（X05）：把请求收窄到**平台硬上限**，非法请求在任何 IO 与探测之前被拒绝。
 *
 * <p>上限都是平台冻结值（可单测），不来自请求、不来自端点、不来自供应商：
 * <ul>
 *   <li>会话寿命 60–1800 秒（默认 600）：会话是在线资源，必须有限；到期即关闭且不续期；</li>
 *   <li>票据 120 秒：短期凭证只用于媒体面（重）建立与续票，到期前可续，不延长会话寿命；</li>
 *   <li>输入缓冲 4 MiB：背压上限，超限按稳定原因结束会话；</li>
 *   <li>同一主体并发 2、同一应用并发 32：超限拒绝（429），不排队；</li>
 *   <li>重连 3 次、断线窗口 60 秒：有界恢复；</li>
 *   <li>工具参数 4000 字节（与列宽一致）、事件文本 4000 字符。</li>
 * </ul>
 */
@Component
public class AiRealtimeParams {

    /** 会话寿命下限（秒）。 */
    public static final int MIN_SESSION_SECONDS = 60;

    /** 会话寿命上限（秒）。 */
    public static final int MAX_SESSION_SECONDS = 1800;

    /** 会话寿命默认值（秒）。 */
    public static final int DEFAULT_SESSION_SECONDS = 600;

    /** 票据寿命（秒）：短期凭证，到期前可续票。 */
    public static final int TICKET_TTL_SECONDS = 120;

    /** 输入音频有界缓冲上限（4 MiB）。 */
    public static final long INPUT_CAPACITY_BYTES = 4L * 1024 * 1024;

    /** 同一主体的并发会话上限。 */
    public static final int MAX_CONCURRENT_SESSIONS_PER_SUBJECT = 2;

    /** 同一应用的并发会话上限。 */
    public static final int MAX_CONCURRENT_SESSIONS_PER_APPLICATION = 32;

    /** 断线重连次数上限。 */
    public static final int MAX_REATTACH_ATTEMPTS = 3;

    /** 断线重连时限（秒；到期未回即关闭）。 */
    public static final int REATTACH_WINDOW_SECONDS = 60;

    /** 失败探测的冷却窗口（秒）：冷却内不重复外发探测。 */
    public static final int PROBE_COOLDOWN_SECONDS = 60;

    /** 受理幂等键上限（与列宽一致）。 */
    public static final int MAX_REQUEST_KEY_LENGTH = 40;

    /** 工具参数 JSON 上限（与列宽一致）。 */
    public static final int MAX_ARGUMENTS_JSON_LENGTH = 4000;

    /** 单条转写文本上限（与列宽一致）。 */
    public static final int MAX_TRANSCRIPT_LENGTH = 4000;

    /** 幂等键形状（与 O01/O04 同口径：字母数字与下划线/短横线）。 */
    private static final Pattern REQUEST_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,40}$");

    /** 收窄受理请求的幂等键。 */
    public String requireRequestKey(String requestKey) {
        if (!StringUtils.hasText(requestKey)
                || requestKey.length() > MAX_REQUEST_KEY_LENGTH
                || !REQUEST_KEY_PATTERN.matcher(requestKey).matches()) {
            throw exception(AI_REQUEST_INVALID);
        }
        return requestKey;
    }

    /** 收窄端点编号。 */
    public Long requireEndpointId(Long endpointId) {
        if (endpointId == null || endpointId <= 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        return endpointId;
    }

    /** 解析协议；未知取值按不合规拒绝（不猜测、不回退）。 */
    public RealtimeProtocol requireProtocol(String protocol) {
        Optional<RealtimeProtocol> parsed = RealtimeProtocol.parse(protocol);
        if (parsed.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        return parsed.get();
    }

    /** 解析音频格式规范形式；非法形状按不合规拒绝，非法取值按格式不支持拒绝。 */
    public RealtimeAudioFormat requireAudioFormat(String canonicalForm) {
        if (!StringUtils.hasText(canonicalForm)) {
            throw exception(AI_REQUEST_INVALID);
        }
        return RealtimeAudioFormat.parse(canonicalForm)
                .orElseThrow(() -> exception(AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED));
    }

    /** 收窄会话寿命（秒）：为空取默认值，越界拒绝（不静默夹紧）。 */
    public int normalizeSessionSeconds(Integer sessionSeconds) {
        if (sessionSeconds == null) {
            return DEFAULT_SESSION_SECONDS;
        }
        if (sessionSeconds < MIN_SESSION_SECONDS || sessionSeconds > MAX_SESSION_SECONDS) {
            throw exception(AI_REQUEST_INVALID);
        }
        return sessionSeconds;
    }
}
