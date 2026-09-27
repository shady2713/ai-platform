package com.basicframework.module.ai.compatibility;

import com.basicframework.framework.ai.core.model.ModelEvent;
import com.basicframework.framework.ai.core.model.ModelResponse;
import com.basicframework.framework.ai.core.model.ModelUsage;
import com.basicframework.framework.ai.core.model.StructuredModelResult;

/**
 * 基线行为黄金值（AT-065「升级前后行为一致」的 before 侧）。
 *
 * <p>首发没有历史产物，before 侧是**冻结基线夹具**在冻结候选（Spring AI 1.1.8 + Boot 3.5.16）
 * 上实测得到的行为快照：把平台契约对象规范化成稳定文本后逐字比对。升级候选时同一份黄金值
 * 保持不变，行为一旦变化即由断言给出 expected/actual 差异；确属有意变化时，必须先改
 * {@code docs/contracts} 与升级记录，再更新这里的黄金值。
 */
final class ProtocolBaselineGolden {

    private ProtocolBaselineGolden() {}

    /** 文本链路：内容/finishReason/用量。 */
    static final String TEXT_SNAPSHOT = "text=pong from mock; finishReason=stop; usage=7/3/estimated=false; tools=0";

    /** 流式链路：DELTA 顺序、工具调用聚合结果、终态用量与结束原因。 */
    static final String STREAM_SNAPSHOT = String.join(
            "\n",
            "DELTA[pong]",
            "DELTA[ from mock]",
            "TOOL_CALL[id=call_probe_1, name=platform_probe, arguments={\"value\":\"ping\"}]",
            "COMPLETED[usage=7/5/estimated=false, finishReason=stop]");

    /** 结构化输出链路：修复后的紧凑 JSON（键序即解析序）。 */
    static final String STRUCTURED_SNAPSHOT =
            "json={\"metric\":\"八月华东净销售额\",\"value\":740}; usage=7/3; finishReason=stop";

    /** 用量缺失语义：UNKNOWN 而非假 0。 */
    static final String UNKNOWN_USAGE_SNAPSHOT =
            "prompt=null; completion=null; total=null; known=false; estimated=false";

    /** 把平台文本响应规范化成稳定单行文本，供与 {@link #TEXT_SNAPSHOT} 比对。 */
    static String snapshot(ModelResponse response) {
        return "text=" + response.text()
                + "; finishReason=" + response.finishReason()
                + "; usage=" + snapshot(response.usage())
                + "; tools=" + response.toolCalls().size();
    }

    static String snapshot(ModelUsage usage) {
        return usage.promptTokens() + "/" + usage.completionTokens() + "/estimated=" + usage.estimated();
    }

    /** 把平台事件序列规范化成逐行文本，供与 {@link #STREAM_SNAPSHOT} 比对。 */
    static String snapshot(java.util.List<ModelEvent> events) {
        StringBuilder builder = new StringBuilder();
        for (ModelEvent event : events) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            switch (event.type()) {
                case DELTA -> builder.append("DELTA[").append(event.text()).append(']');
                case TOOL_CALL ->
                    builder.append("TOOL_CALL[id=")
                            .append(event.toolCall().id())
                            .append(", name=")
                            .append(event.toolCall().name())
                            .append(", arguments=")
                            .append(event.toolCall().argumentsJson())
                            .append(']');
                case COMPLETED ->
                    builder.append("COMPLETED[usage=")
                            .append(snapshot(event.usage()))
                            .append(", finishReason=")
                            .append(event.finishReason())
                            .append(']');
            }
        }
        return builder.toString();
    }

    /** 把结构化输出结果规范化成稳定文本，供与 {@link #STRUCTURED_SNAPSHOT} 比对。 */
    static String snapshot(StructuredModelResult result) {
        return "json=" + result.json() + "; usage=" + result.usage().promptTokens() + "/"
                + result.usage().completionTokens() + "; finishReason=" + result.finishReason();
    }
}
