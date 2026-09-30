package com.basicframework.module.ai.dal.dataobject.realtime;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.basicframework.framework.mybatis.core.dataobject.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 实时会话事件（X05）：转写、下行音频、工具调用请求、过期丢弃、重连与关闭的**只追加**留痕。
 *
 * <p>为什么事件要落库而不是只放在内存：断线恢复必须能重建界面（转写与工具状态），
 * 审计要能回答"打断了什么、哪些旧帧被丢弃"。事件只保留受控字段（文本、字节数、稳定码），
 * 不含提示词、上游报文与任何凭据。
 *
 * <p>去重由 {@code dedupKey} 的唯一键保证：上游事件重放（重连后重复拉取）不会产生重复行。
 */
@TableName("ai_realtime_event")
@KeySequence("ai_realtime_event_seq")
@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiRealtimeEventDO extends BaseDO {

    /** 事件类型：转写（中间结果与定稿）。 */
    public static final String TYPE_TRANSCRIPT = "TRANSCRIPT";

    /** 事件类型：下行音频帧（只记字节数与序号，不记音频内容）。 */
    public static final String TYPE_AUDIO = "AUDIO";

    /** 事件类型：工具调用请求（详情在 ai_realtime_tool_call）。 */
    public static final String TYPE_TOOL_CALL = "TOOL_CALL";

    /** 事件类型：因回合过期被丢弃的帧/事件（必须显式记账，不允许静默丢弃）。 */
    public static final String TYPE_STALE_DROPPED = "STALE_DROPPED";

    /** 事件类型：重连（记录第几次、何时）。 */
    public static final String TYPE_REATTACHED = "REATTACHED";

    /** 事件类型：会话关闭（稳定原因码）。 */
    public static final String TYPE_CLOSED = "CLOSED";

    /** 事件编号 */
    @TableId
    private Long id;

    /** 会话编号 */
    private Long sessionId;

    /** 事件类型（TRANSCRIPT/AUDIO/TOOL_CALL/STALE_DROPPED/REATTACHED/CLOSED） */
    private String eventType;

    /** 事件所属回合 */
    private Long turnNo;

    /** 回合内事件序号（0 表示与序号无关的会话级事件） */
    private Long eventSeq;

    /** 转写文本（不含提示词与上游报文；其他类型为空串） */
    private String textContent;

    /** 音频字节数（仅 AUDIO 事件有值） */
    private Integer byteCount;

    /** 稳定明细（结束原因码/工具调用标识/丢弃原因；不含上游正文） */
    private String detailCode;

    /** 事件去重键（同一会话内唯一：重放不产生重复行） */
    private String dedupKey;
}
