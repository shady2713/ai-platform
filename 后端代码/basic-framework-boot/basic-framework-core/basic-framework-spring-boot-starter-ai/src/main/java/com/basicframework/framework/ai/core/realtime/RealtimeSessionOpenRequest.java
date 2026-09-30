package com.basicframework.framework.ai.core.realtime;

import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import java.time.LocalDateTime;

/**
 * 打开实时会话通道的请求（X05 冻结）：受理时固定的会话事实 + 受控端点快照。
 *
 * <p>通道是**实例本地**资源（真实媒体连接天然绑定到一个进程），会话行是跨实例的事实：
 * 平台按会话行做归属、到期、并发与幂等判定，通道只承载一次会话的实际媒体/事件往返。
 *
 * @param sessionKey     会话业务键（平台生成，用于适配器侧的幂等与排障；不含凭据）
 * @param endpoint       端点快照（受理时固定的配置/凭据版本）
 * @param protocol       会话协议（受理时固定）
 * @param audioFormat    会话音频格式（受理时固定）
 * @param inputCapacityBytes 输入音频有界缓冲上限（背压判据，受理时固定）
 * @param expiresTime    会话绝对到期时间（受理时固定；到期即关闭，不续期）
 */
public record RealtimeSessionOpenRequest(
        String sessionKey,
        ModelEndpointSnapshot endpoint,
        RealtimeProtocol protocol,
        RealtimeAudioFormat audioFormat,
        long inputCapacityBytes,
        LocalDateTime expiresTime) {

    public RealtimeSessionOpenRequest {
        if (sessionKey == null || sessionKey.isBlank()) {
            throw new IllegalArgumentException("会话业务键不能为空");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("端点快照不能为空");
        }
        if (protocol == null) {
            throw new IllegalArgumentException("协议不能为空");
        }
        if (audioFormat == null) {
            throw new IllegalArgumentException("音频格式不能为空");
        }
        if (inputCapacityBytes <= 0) {
            throw new IllegalArgumentException("输入缓冲上限必须为正：" + inputCapacityBytes);
        }
        if (expiresTime == null) {
            throw new IllegalArgumentException("到期时间不能为空");
        }
    }
}
