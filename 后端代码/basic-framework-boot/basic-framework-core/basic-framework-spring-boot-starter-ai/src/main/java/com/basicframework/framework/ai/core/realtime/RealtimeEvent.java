package com.basicframework.framework.ai.core.realtime;

import java.util.Map;

/**
 * 实时会话事件（X05 冻结）：适配器把上游事件翻译成这四种**平台词汇**，平台按回合栅栏消费。
 *
 * <p>约束：
 * <ul>
 *   <li>每个事件都带回合序号：平台只消费当前回合的事件，旧回合事件（打断前已在飞）一律丢弃；</li>
 *   <li>工具调用只是**数据**（{@link ToolCall}）：平台不自动执行，执行必须经 X06 的受控入口；</li>
 *   <li>结束事件带稳定原因（{@link RealtimeCloseReason}），不含上游报文；失败细节只允许出现稳定码
 *       （{@link Ended#detailCode()} 由适配层给出，平台不转发上游文本）。</li>
 * </ul>
 */
public sealed interface RealtimeEvent {

    /** 事件所属回合。 */
    long turnNo();

    /** 转写事件：{@code finalSegment=false} 是中间结果，为真表示该段已定稿。 */
    record Transcript(long turnNo, long seq, boolean finalSegment, String text) implements RealtimeEvent {

        public Transcript {
            if (seq < 0) {
                throw new IllegalArgumentException("事件序号不能为负：" + seq);
            }
            if (text == null || text.isBlank()) {
                // 空白文本不是"有效的中间结果"：平台不把空转写写进事件面
                throw new IllegalArgumentException("转写文本不能为空");
            }
        }
    }

    /** 下行音频事件：{@code last=true} 表示该回合输出结束。 */
    record Audio(long turnNo, long seq, int byteCount, boolean last) implements RealtimeEvent {

        public Audio {
            if (seq < 0) {
                throw new IllegalArgumentException("事件序号不能为负：" + seq);
            }
            if (byteCount <= 0) {
                throw new IllegalArgumentException("下行音频字节数必须为正：" + byteCount);
            }
        }
    }

    /** 工具调用请求（只作为数据；执行走平台受控入口）。 */
    record ToolCall(long turnNo, String callId, String toolCode, Map<String, Object> arguments)
            implements RealtimeEvent {

        public ToolCall {
            if (callId == null || callId.isBlank()) {
                throw new IllegalArgumentException("工具调用标识不能为空");
            }
            if (toolCode == null || toolCode.isBlank()) {
                throw new IllegalArgumentException("工具标识不能为空");
            }
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** 上游结束会话（或适配器明确失败）：带稳定原因与稳定明细码。 */
    record Ended(long turnNo, RealtimeCloseReason reason, String detailCode) implements RealtimeEvent {

        public Ended {
            if (reason == null) {
                throw new IllegalArgumentException("结束原因不能为空");
            }
        }
    }
}
