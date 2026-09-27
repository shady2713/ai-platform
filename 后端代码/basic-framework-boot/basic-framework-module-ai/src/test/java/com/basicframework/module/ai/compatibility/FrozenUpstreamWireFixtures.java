package com.basicframework.module.ai.compatibility;

import java.util.List;

/**
 * 冻结的上游模型协议夹具（AT-065 基线模式）。
 *
 * <p>来源与证据层级（不把夹具说成比实际更强的东西）：
 * <ul>
 *   <li>{@link #RECORDED_TEXT_REQUEST_JSON}、{@link #RECORDED_EMBEDDING_REQUEST_JSON}：
 *       F02 协议实验**实测记录**的 OpenAI 兼容请求体，逐字取自
 *       {@code .local-state/f02-upstream/mock-requests.jsonl}（2026-09-16 与 2026-09-26 两次实验一致，
 *       见 {@code docs/integrations/ai-platform-upstream-candidates.md} §4/§10.6）；</li>
 *   <li>{@link #TEXT_RESPONSE_JSON}、{@link #STREAM_CHUNK_JSONS}、{@link #STRUCTURED_OUTPUT_TEXT}：
 *       **基线夹具**。首发没有 N-1 历史产物（Spring AI 1.1.8 是平台首个冻结候选），按任务卡 §4
 *       "首发无历史版本时报告为基线兼容夹具验证"处理：形状按 F02/M03 mock 端点观测结果冻结
 *       （文本内容 {@code pong from mock} 见 F02 §10.6 与 M03 证据），首次真实升级时用已发布旧产物替换/补充。</li>
 * </ul>
 *
 * <p>响应夹具保持上游协议原样（未经平台适配器加工）；用例把它喂给**真实适配器**再与
 * {@link ProtocolBaselineGolden} 比对，从而把"升级是否改变行为"变成可复现的断言。
 */
final class FrozenUpstreamWireFixtures {

    private FrozenUpstreamWireFixtures() {}

    /** F02 实测记录的文本请求体（model 为实验端点值，其余字段即平台发出的线上形状）。 */
    static final String RECORDED_TEXT_REQUEST_JSON =
            "{\"messages\": [{\"content\": \"ping\", \"role\": \"user\"}], \"model\": \"probe-model\","
                    + " \"stream\": false, \"temperature\": 0.0}";

    /** F02 实测记录的嵌入请求体（本包只用于证明冻结范围，嵌入链路由 M04 用例覆盖）。 */
    static final String RECORDED_EMBEDDING_REQUEST_JSON =
            "{\"input\": [\"hello embedding\"], \"model\": \"probe-embedding\"}";

    /** 基线文本响应：单 choice + finish_reason + usage。 */
    static final String TEXT_RESPONSE_JSON =
            """
            {
              "id": "chatcmpl-baseline-text",
              "object": "chat.completion",
              "created": 1789600000,
              "model": "gpt-4o-mini",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "pong from mock"},
                  "finish_reason": "stop"
                }
              ],
              "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}
            }
            """;

    /** 基线文本响应但上游未给 usage（AT-060：必须收敛为 UNKNOWN，不得写成假 0）。 */
    static final String TEXT_RESPONSE_WITHOUT_USAGE_JSON =
            """
            {
              "id": "chatcmpl-baseline-no-usage",
              "object": "chat.completion",
              "created": 1789600000,
              "model": "gpt-4o-mini",
              "choices": [
                {
                  "index": 0,
                  "message": {"role": "assistant", "content": "pong from mock"},
                  "finish_reason": "stop"
                }
              ]
            }
            """;

    /** 基线流式分片：文本增量两个、工具调用参数分片两个、终态（finish_reason + usage）一个。 */
    static final List<String> STREAM_CHUNK_JSONS = List.of(
            """
            {"id":"chatcmpl-baseline-stream","object":"chat.completion.chunk","created":1789600000,
             "model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"pong"},
             "finish_reason":null}]}
            """,
            """
            {"id":"chatcmpl-baseline-stream","object":"chat.completion.chunk","created":1789600000,
             "model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":" from mock"},
             "finish_reason":null}]}
            """,
            """
            {"id":"chatcmpl-baseline-stream","object":"chat.completion.chunk","created":1789600000,
             "model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"",
             "tool_calls":[{"index":0,"id":"call_probe_1","type":"function",
             "function":{"name":"platform_probe","arguments":"{\\"value\\""}}]},"finish_reason":null}]}
            """,
            """
            {"id":"chatcmpl-baseline-stream","object":"chat.completion.chunk","created":1789600000,
             "model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"",
             "tool_calls":[{"index":0,"id":"call_probe_1","type":"function",
             "function":{"name":"platform_probe","arguments":":\\"ping\\"}"}}]},"finish_reason":null}]}
            """,
            """
            {"id":"chatcmpl-baseline-stream","object":"chat.completion.chunk","created":1789600000,
             "model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":""},
             "finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":5,"total_tokens":12}}
            """);

    /** 结构化输出的输出契约（平台把 Schema 嵌入提示词作为上游输出契约）。 */
    static final String STRUCTURED_SCHEMA_JSON = "{\"type\":\"object\",\"required\":[\"metric\",\"value\"],"
            + "\"properties\":{\"metric\":{\"type\":\"string\"},\"value\":{\"type\":\"number\"}}}";

    /** 基线结构化输出：带 Markdown 围栏（触发有界修复第 1 步），数值为整数避免浮点格式歧义。 */
    static final String STRUCTURED_OUTPUT_TEXT =
            """
            ```json
            {
              "metric": "八月华东净销售额",
              "value": 740
            }
            ```
            """;

    /** 基线错误：上游报文里带金丝雀，适配层必须只给稳定原因、不回传厂商报文。 */
    static final String UPSTREAM_FAILURE_CANARY = "upstream body with sk-q08-must-not-leak";
}
