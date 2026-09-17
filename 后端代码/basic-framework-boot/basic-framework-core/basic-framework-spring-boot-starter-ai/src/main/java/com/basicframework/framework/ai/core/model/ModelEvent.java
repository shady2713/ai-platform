package com.basicframework.framework.ai.core.model;

/**
 * 平台自有模型事件（M03 冻结）：把上游文本增量、工具调用与用量统一成稳定词汇。
 *
 * <p>事件序列约定：
 * <ul>
 *   <li>若干 {@link Type#DELTA}（文本增量，可能为空序列）；</li>
 *   <li>若干个 {@link Type#TOOL_CALL}（工具调用请求，只暴露数据，永不自动执行）；</li>
 *   <li>恰好一个 {@link Type#COMPLETED} 作为终态，携带用量与结束原因（用量可能为 UNKNOWN）。</li>
 * </ul>
 * 失败不作为事件表达：{@link ModelStream} 直接抛出 {@link ModelException}，原因取自
 * {@link ModelException.Reason}，消息与事件都不含凭据或上游原始报文。
 */
public record ModelEvent(Type type, String text, ModelToolCall toolCall, ModelUsage usage, String finishReason) {

    /** 事件类型。 */
    public enum Type {
        /** 文本增量。 */
        DELTA,
        /** 工具调用请求（数据，不自动执行）。 */
        TOOL_CALL,
        /** 正常结束（携带用量与结束原因）。 */
        COMPLETED
    }

    /** 事件字段必须与其类型自洽，避免下游对不可能的字段组合做分支。 */
    public ModelEvent {
        if (type == null) {
            throw new IllegalArgumentException("事件类型不能为空");
        }
        switch (type) {
            case DELTA -> {
                if (text == null) {
                    throw new IllegalArgumentException("文本增量事件必须携带文本");
                }
            }
            case TOOL_CALL -> {
                if (toolCall == null) {
                    throw new IllegalArgumentException("工具调用事件必须携带调用请求");
                }
            }
            case COMPLETED -> {
                if (usage == null) {
                    usage = ModelUsage.UNKNOWN;
                }
            }
        }
    }

    /** 文本增量事件。 */
    public static ModelEvent delta(String text) {
        return new ModelEvent(Type.DELTA, text, null, null, null);
    }

    /** 工具调用事件。 */
    public static ModelEvent toolCall(ModelToolCall toolCall) {
        return new ModelEvent(Type.TOOL_CALL, null, toolCall, null, null);
    }

    /** 结束事件；用量缺失时传 {@link ModelUsage#UNKNOWN}，表示 UNKNOWN 而非 0。 */
    public static ModelEvent completed(ModelUsage usage, String finishReason) {
        return new ModelEvent(Type.COMPLETED, null, null, usage, finishReason);
    }

    /** 是否终态（终态之后不会再有事件）。 */
    public boolean isTerminal() {
        return type == Type.COMPLETED;
    }
}
