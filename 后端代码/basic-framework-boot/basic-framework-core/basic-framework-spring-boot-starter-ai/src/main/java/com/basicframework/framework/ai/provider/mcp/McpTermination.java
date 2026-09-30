package com.basicframework.framework.ai.provider.mcp;

/**
 * MCP 一次发现的**终止原因**（X07）。
 *
 * <p>为什么"终止原因"必须显式：远程断线时最危险的失败模式不是报错，而是**静默降级**——
 * 连不上就返回"该服务器没有任何工具"，于是上层把"工具消失"当成事实继续运行（更糟的是把
 * 已审批工具当成被上游删除而静默下线）。因此本适配器把每一次发现的结局都收敛成这��枚举，
 * 且只有 {@link #SUCCESS} 允许携带工具清单；其余取值一律**没有工具清单**，调用方必须当成失败处理。
 *
 * <p>另外两个刻意的设计：
 * <ul>
 *   <li>{@link #RETRIES_EXHAUSTED} 与 {@link #TIMEOUT}/{@link #UNREACHABLE} 分开：前者是"重试了
 *       仍有界地耗尽"，后者是"最后一次尝试的失败形态"，运维排障需要区分二者；</li>
 *   <li>没有 {@code UNKNOWN}/{@code UNSPECIFIED} 之类的"再等等看"取值——任何不确定都必须落到
 *       某个具体终止原因上，不给上层"要不要继续"留口子。</li>
 * </ul>
 */
public enum McpTermination {

    /** 成功：拿到完整工具清单（{@code tools/list} 正常返回）。 */
    SUCCESS,

    /** 有界重试全部耗尽仍未成功（最后一次失败形态见具体原因码/异常）。 */
    RETRIES_EXHAUSTED,

    /** 连接或请求超时（已按端点声明的连接/请求超时截断）。 */
    TIMEOUT,

    /** 授权被拒（401/403 或传输层授权异常）：不重试——重试同样的令牌没有意义。 */
    UNAUTHORIZED,

    /** 网络不可达（连接被拒、DNS 失败等）。 */
    UNREACHABLE,

    /** 协议层错误：响应不是合法 MCP 消息、能力协商失败、工具清单缺失。 */
    PROTOCOL_ERROR,

    /** 端点地址不被允许：清单未命中、非 https、私网未批准等（默认拒绝，不发出任何请求）。 */
    ADDRESS_DENIED,

    /** 协议版本漂移：服务端协商出的版本不在平台允许清单内（默认拒绝，不采用降级版本）。 */
    PROTOCOL_VERSION_UNSUPPORTED,

    /** 响应超出上限（工具条数或响应体过大）：拒绝而不是截断（半截清单会被当成"就这些工具"）。 */
    RESPONSE_TOO_LARGE
}
