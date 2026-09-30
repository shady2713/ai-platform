package com.basicframework.module.ai.dal.dataobject.realtime;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.SoftDeletableDO;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 实时语音会话（X05，FR-37）：一次在线会话的全部**受理时固定事实**与在线状态。
 *
 * <p>受理即固定：端点、配置版本、凭据版本、模型标识、协议、音频格式、输入缓冲上限、
 * 票据摘要与绝对到期时间写入本行；之后改端点配置不改变已受理会话的语义（与会话重连无关）。
 *
 * <p>状态机：{@code OPEN → DETACHED → OPEN}（有界重连）或 { OPEN/DETACHED → CLOSED }（终态）。
 * 关闭是**惰性**的：到期、切用户、背压超限都在读取/使用时判定并写终态，不新增常驻扫描任务
 * （X10 教训：无界扫描把 NFR-04 accept p95 从 ≈160ms 打到 904ms）。
 *
 * <p>票据只存 SHA-256 摘要（{@code ticketDigest}）：明文只在受理/续票响应出现一次；
 * 摘要同样不进 toString（{@code @ToString.Exclude}）。
 */
@TableName("ai_realtime_session")
@KeySequence("ai_realtime_session_seq")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiRealtimeSessionDO extends SoftDeletableDO {

    /** 状态：在线（可推流、可打断、可关麦）。 */
    public static final String STATUS_OPEN = "OPEN";

    /** 状态：断线待重连（媒体通道已断，仍可在时限与次数内重连并恢复）。 */
    public static final String STATUS_DETACHED = "DETACHED";

    /** 状态：已关闭（终态；关闭原因稳定码给出为什么）。 */
    public static final String STATUS_CLOSED = "CLOSED";

    /** 会话编号 */
    @TableId
    private Long id;

    /** 会话业务键（平台生成，用于适配器幂等与排障；不含凭据） */
    private String sessionKey;

    /** 受理幂等键（调用方提供，主体内唯一：重复受理返回同一会话） */
    private String requestKey;

    /** 应用编号（归属之一） */
    private Long applicationId;

    /** 主体类型（APP/USER） */
    private String subjectType;

    /** 可信外部用户标识（归属之一） */
    private String externalUserId;

    /** 受理时固定的模型端点编号 */
    private Long endpointId;

    /** 受理时固定的端点配置版本 */
    private Integer endpointConfigRevision;

    /** 受理时固定的端点凭据版本（只记录版本号，不含凭据；按敏感字段口径不进 toString） */
    @ToString.Exclude
    private Integer endpointCredentialRevision;

    /** 受理时固定的模型标识（非秘密配置） */
    private String modelRef;

    /** 协议（WEBSOCKET/WEBRTC；受理时固定，重连只能沿用） */
    private String protocol;

    /** 音频格式规范形式（如 audio/pcm@16000:1:20） */
    private String audioFormat;

    /** 状态（OPEN/DETACHED/CLOSED） */
    private String status;

    /** 结束原因稳定码（未关闭为空） */
    private String closeReason;

    /** 当前回合号（打断时 +1；晚到的旧回合事件按过期丢弃） */
    private Long turnNo;

    /** 因回合过期被丢弃的帧/事件累计数（"不得静默丢弃"的证明） */
    private Integer droppedStaleFrames;

    /** 输入音频有界缓冲上限（受理时固定） */
    private Long inputCapacityBytes;

    /** 当前占用字节（有界；超限即结束会话） */
    private Long bufferedBytes;

    /** 累计接受的输入字节 */
    private Long inputBytesTotal;

    /** 是否已关麦（关麦期间上行音频一律拒绝） */
    private Boolean muted;

    /** 已用重连次数（有界） */
    private Integer resumeAttempts;

    /** 重连时限（断线后超过即关闭；在线态等于会话到期时间） */
    private LocalDateTime resumeDeadline;

    /** 短期票据的 SHA-256 摘要（明文只在受理/续票响应出现一次） */
    @ToString.Exclude
    private String ticketDigest;

    /** 票据代次（每次续票 +1；旧票据立即失效） */
    private Integer ticketRevision;

    /** 票据到期时间（不晚于会话绝对到期时间） */
    private LocalDateTime ticketExpiresTime;

    /** 会话绝对到期时间（受理时固定，不续期） */
    private LocalDateTime expiresTime;

    /** 乐观锁版本 */
    private Integer version;
}
