package com.basicframework.framework.ai.core.realtime;

import java.util.Arrays;

/**
 * 一帧上行音频（X05 冻结）：只携带**回合序号 + 帧序号 + 字节**，不含任何解释性字段。
 *
 * <p>回合序号是打断栅栏的载体：平台只接受 {@code turnNo} 等于会话当前回合的帧，更小的一律按
 * "过期帧"丢弃并计数（晚到的旧回合音频绝不继续输出），更大的一律拒绝（客户端不能凭空发明回合）。
 *
 * <p>防御性复制：构造时复制字节，避免调用方在帧进入缓冲后改写内容。
 *
 * @param turnNo  回合序号（从 0 开始；打断时 +1）
 * @param frameSeq 帧序号（同一回合内从 0 开始递增，用于计账与去重展示）
 * @param payload 音频字节（大小受会话固定的 {@link RealtimeAudioFormat#maxFrameBytes()} 约束）
 */
public record RealtimeAudioFrame(long turnNo, long frameSeq, byte[] payload) {

    public RealtimeAudioFrame {
        if (turnNo < 0) {
            throw new IllegalArgumentException("回合序号不能为负：" + turnNo);
        }
        if (frameSeq < 0) {
            throw new IllegalArgumentException("帧序号不能为负：" + frameSeq);
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("音频帧不能为空");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }

    /** 字节数（背压计账的唯一口径）。 */
    public int byteCount() {
        return payload.length;
    }

    @Override
    public String toString() {
        return "RealtimeAudioFrame[turnNo=" + turnNo + ", frameSeq=" + frameSeq + ", byteCount=" + payload.length + "]";
    }
}
