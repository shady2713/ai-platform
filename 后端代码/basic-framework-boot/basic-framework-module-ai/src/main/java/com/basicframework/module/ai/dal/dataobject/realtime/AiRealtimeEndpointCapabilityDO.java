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
 * 实时端点能力验证台账（X05，ADR 0052）：每个（端点, 配置版本, 协议）的**唯一**验证结论。
 *
 * <p>为什么必须落库而不是只看配置：实时语音协议与能力不能按供应商/模型名推断，平台的发布范围是
 * "适配器声明 ∩ 真实探测确认 ∩ 协议必需能力"。台账记录这三者的结果与稳定明细码，
 * 端点配置版本变化后旧结论不再覆盖当前配置（必须重探）。
 *
 * <p>结论只含稳定码与耗时：不含凭据、上游报文与音频内容。失败结论在冷却窗口内不重复外发
 * （避免把会话受理变成探测风暴）。
 */
@TableName("ai_realtime_endpoint_capability")
@KeySequence("ai_realtime_endpoint_capability_seq")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiRealtimeEndpointCapabilityDO extends SoftDeletableDO {

    /** 结论：真实探测确认可用。 */
    public static final String STATUS_VERIFIED = "VERIFIED";

    /** 结论：明确不支持（适配器未实现、协议不匹配、音频格式不支持）。 */
    public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";

    /** 结论：尝试过但失败（超时、限流、协议错误等）。 */
    public static final String STATUS_FAILED = "FAILED";

    /** 结论编号 */
    @TableId
    private Long id;

    /** 模型端点编号 */
    private Long endpointId;

    /** 被验证的端点配置版本 */
    private Integer configRevision;

    /** 被验证的协议（WEBSOCKET/WEBRTC） */
    private String protocol;

    /** 结论（VERIFIED/UNSUPPORTED/FAILED） */
    private String status;

    /** 稳定明细码（缺失能力名/失败原因名；不含上游报文） */
    private String detailCode;

    /** 适配器声明的能力（逗号分隔的能力名） */
    private String declaredCapabilities;

    /** 真实探测确认的能力（逗号分隔的能力名） */
    private String confirmedCapabilities;

    /** 验证通过的音频格式规范形式（逗号分隔） */
    private String audioFormats;

    /** 真实探测耗时（毫秒） */
    private Long latencyMillis;

    /** 探测时间（失败结论在冷却窗口内不重复外发） */
    private LocalDateTime probedTime;

    /** 乐观锁版本 */
    private Integer version;
}
