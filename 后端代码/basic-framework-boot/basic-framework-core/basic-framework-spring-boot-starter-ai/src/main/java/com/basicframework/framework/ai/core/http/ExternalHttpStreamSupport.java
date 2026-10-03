package com.basicframework.framework.ai.core.http;

/**
 * 受控出站的**流式入口**（F11）。
 *
 * <p>与 {@link ExternalHttpClient}（请求/响应入口）**并存而非替换**：闸门完全相同——同一份允许清单、
 * 同一套私网判定、同一份协议约束与请求头卫生、同一处超时取值。差别只是响应体**逐块交付**，
 * 因此流式通道不再受"整包读完才交付"拖累。
 *
 * <p>为什么是独立接口而不是给 {@code ExternalHttpClient} 追加方法：{@code ExternalHttpClient} 是 F09
 * 冻结的契约，平台内外还有多个实现（连接器、Webhook 与各测试夹具），追加抽象方法会强制它们全部改造
 * （那些文件不在本卡允许范围内）。用能力接口表达后，不支持流式的实现会在能力检查处**显式失败**，
 * 而不是悄悄退回到不受治理的整包读取。
 */
public interface ExternalHttpStreamSupport {

    /**
     * 打开一个流式出站响应。
     *
     * <p>请求期治理与 {@code execute} 完全一致：允许清单、私网判定、协议约束与请求头卫生都在
     * **发出任何字节之前**判定；被拒时抛 {@link ExternalHttpException}，请求不离开进程。
     *
     * @param request 服务端构造的请求
     * @return 已建立连接、等待调用方逐块消费的响应（调用方必须
     *         {@link ExternalHttpStreamResponse#close()} 释放上游连接）
     * @throws ExternalHttpException 目标未允许、私网被拒、请求不合法、连接失败或等待响应头超时
     */
    ExternalHttpStreamResponse openStream(ExternalHttpRequest request);
}
