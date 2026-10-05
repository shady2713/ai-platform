package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.model.ModelException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring AI 客户端到 F09 受控出站边界的桥（M07）。
 *
 * <p>背景：M02 只在**创建期**校验出站允许清单，Spring AI 自己的 RestClient/WebClient 走默认传输，
 * 每一个实际发出的请求都不再过守卫。本类把两种传输都换掉：
 * <ul>
 *   <li>阻塞通道（聊天同步、嵌入、转写、语音合成）用 {@link RestClient.Builder#requestFactory} 换成
 *       {@link GuardedClientHttpRequestFactory}；</li>
 *   <li>响应式通道（聊天流式、音频流式）用 {@link WebClient.Builder#exchangeFunction} 换成
 *       {@link GuardedExchangeFunction}。</li>
 * </ul>
 * 四条模型通道实际用哪个客户端由 Spring AI 1.1.8 的字节码实测确认（见证据文档）：
 * {@code OpenAiApi} 的 {@code chatCompletionEntity}/{@code embeddings} 用 {@code restClient}，
 * {@code chatCompletionStream} 用 {@code webClient}；{@code OpenAiAudioApi} 的
 * {@code createSpeech}/{@code createTranscription} 用 {@code restClient}，{@code stream} 用 {@code webClient}。
 *
 * <p>请求头卫生：JDK HttpClient 自行重算 Host/Content-Length/Transfer-Encoding/Connection，
 * 适配器不把这些头交给守卫（否则守卫会按"请求卫生"拒绝一个本来合法的请求），
 * 凭据类头（Cookie/Set-Cookie）**不剥离**，仍然交给守卫按 F09 规则拒绝。
 */
public class GuardedExternalHttpTransport {

    /** 由底层 JDK HttpClient 重算的传输层头：适配器不转发，交给底层生成。 */
    private static final Set<String> TRANSPORT_OWNED_HEADERS =
            Set.of("host", "connection", "content-length", "transfer-encoding");

    private final ExternalHttpClient httpClient;

    public GuardedExternalHttpTransport(ExternalHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "出站客户端不能为空");
    }

    /** 阻塞通道的 Spring 客户端构造器（聊天同步、嵌入、转写、语音合成共用）。 */
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestFactory(new GuardedClientHttpRequestFactory(httpClient));
    }

    /** 响应式通道的 Spring 客户端构造器（聊天流式、音频流式共用）。 */
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder().exchangeFunction(new GuardedExchangeFunction(httpClient));
    }

    /**
     * Spring 请求头 → 守卫请求头：剥离底层自算的传输层头，其余（含凭据头）原样交守卫判定。
     * 同名多值头用逗号合并，与守卫的 {@code Map<String,String>} 请求契约一致。
     */
    static Map<String, String> toGuardHeaders(HttpHeaders headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        headers.forEach((name, values) -> {
            if (TRANSPORT_OWNED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            result.put(name, values == null || values.isEmpty() ? "" : String.join(", ", values));
        });
        return result;
    }

    /**
     * 把守卫的稳定拒绝原因映射为平台模型契约的稳定原因；不是守卫拒绝时返回 {@code null}。
     *
     * <p>守卫的原因沿因果链查找：Spring AI 会把请求期异常包进 {@code NonTransientAiException}
     * 等厂商异常里（流式还会包一层 Reactor），只有沿链找到 {@link ExternalHttpException}
     * 才能区分"被出站策略拒绝"与"上游真的失败"。拒绝一律映射为 {@code TARGET_NOT_ALLOWED}
     * （不可重试），因此不会被平台重试放大；消息只保留原因文案，不含上游响应内容与凭据。
     */
    static ModelException.Reason toModelReason(Throwable throwable) {
        ExternalHttpException denied = findDenial(throwable);
        if (denied == null) {
            return null;
        }
        return switch (denied.getReason()) {
            case TARGET_NOT_ALLOWED, PRIVATE_TARGET_DENIED, INVALID_REQUEST -> ModelException.Reason.TARGET_NOT_ALLOWED;
            case TIMEOUT -> ModelException.Reason.TIMEOUT;
            // F12：取消此前以 CONNECT_FAILED 到达这里（F12 之前守卫把它收敛进兜底分支），
            // 归因保持不变——F12 只让守卫侧的 Reason 可区分，不改变模型契约这一层的结论。
            case CANCELLED, CONNECT_FAILED, RESPONSE_TOO_LARGE -> ModelException.Reason.UPSTREAM_FAILED;
        };
    }

    /** 沿因果链找守卫拒绝（限制深度，避免异常自引用导致死循环）。 */
    private static ExternalHttpException findDenial(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ExternalHttpException externalHttpException) {
                return externalHttpException;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }
}
