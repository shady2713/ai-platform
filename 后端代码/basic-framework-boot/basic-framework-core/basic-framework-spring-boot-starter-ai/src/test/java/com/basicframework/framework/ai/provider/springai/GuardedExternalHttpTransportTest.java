package com.basicframework.framework.ai.provider.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.http.AiHttpProperties;
import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpException;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import com.basicframework.framework.ai.core.http.GuardedExternalHttpClient;
import com.basicframework.framework.ai.core.model.ModelException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 适配器契约（M07）：请求头映射、请求级允许清单判定、拒绝原因的稳定映射。
 *
 * <p>这里不经过厂商模型，直接驱动适配器，因此能精确断言"每一次出站请求都重新过守卫"，
 * 并且能构造主机/端口不在允许清单的请求——这类请求在创建期根本不会被工厂看见。
 */
class GuardedExternalHttpTransportTest {

    private static final String NOT_ALLOWED_HOST = "not-allowed.example.com";

    /** 默认拒绝一切：清单里只有 api.example.com，且私网目标未批准（任何请求都应被拒）。 */
    private static AiHttpProperties policy() {
        AiHttpProperties properties = new AiHttpProperties();
        properties.setAllowedHosts(List.of("api.example.com"));
        properties.setAllowedPorts(List.of(443));
        return properties;
    }

    private static GuardedExternalHttpTransport transport(AiHttpProperties properties) {
        return new GuardedExternalHttpTransport(new GuardedExternalHttpClient(properties));
    }

    @Test
    void blockingChannelRefusesHostOutsideAllowlistPerRequest() {
        RestClient client = transport(policy()).restClientBuilder().build();

        assertThatThrownBy(() -> client.get()
                        .uri("https://" + NOT_ALLOWED_HOST + "/v1/chat/completions")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .as("主机不在允许清单：请求期拒绝")
                        .isEqualTo(ExternalHttpException.Reason.TARGET_NOT_ALLOWED));
    }

    @Test
    void blockingChannelRefusesPortOutsideAllowlistPerRequest() {
        RestClient client = transport(policy()).restClientBuilder().build();

        assertThatThrownBy(() -> client.get()
                        .uri("https://api.example.com:8443/v1/embeddings")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .as("端口不在允许清单：请求期拒绝")
                        .isEqualTo(ExternalHttpException.Reason.TARGET_NOT_ALLOWED));
    }

    @Test
    void blockingChannelRefusesCredentialHeaderByGuardRule() {
        RestClient client = transport(policy())
                .restClientBuilder()
                .defaultHeader(HttpHeaders.COOKIE, "session=secret")
                .build();

        assertThatThrownBy(() -> client.get()
                        .uri("https://api.example.com/v1/chat/completions")
                        .retrieve()
                        .toBodilessEntity())
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .as("凭据类头交由守卫按 F09 请求卫生规则拒绝")
                        .isEqualTo(ExternalHttpException.Reason.INVALID_REQUEST));
    }

    @Test
    void reactiveChannelRefusesHostOutsideAllowlistPerRequest() {
        WebClient client = transport(policy()).webClientBuilder().build();

        assertThatThrownBy(() -> client.get()
                        .uri("https://" + NOT_ALLOWED_HOST + "/v1/chat/completions")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block())
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .as("响应式通道同样按请求拒绝")
                        .isEqualTo(ExternalHttpException.Reason.TARGET_NOT_ALLOWED));
    }

    @Test
    void reactiveChannelRefusesPlainHttpWhenPrivateTargetsNotApproved() {
        WebClient client = transport(policy()).webClientBuilder().build();

        assertThatThrownBy(() -> client.post()
                        .uri("http://api.example.com/v1/chat/completions")
                        .bodyValue("{}")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block())
                .isInstanceOf(ExternalHttpException.class)
                .satisfies(exception -> assertThat(((ExternalHttpException) exception).getReason())
                        .as("未批准内网目标时 http 一律拒绝")
                        .isEqualTo(ExternalHttpException.Reason.TARGET_NOT_ALLOWED));
    }

    @Test
    void transportOwnedHeadersAreDroppedButCredentialHeadersAreForwardedForGuardToReject() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Authorization", "Bearer sk-test");
        headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        headers.set(HttpHeaders.HOST, "api.example.com");
        headers.set(HttpHeaders.CONTENT_LENGTH, "2");
        headers.set(HttpHeaders.CONNECTION, "keep-alive");
        headers.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
        headers.set(HttpHeaders.COOKIE, "session=secret");

        var mapped = GuardedExternalHttpTransport.toGuardHeaders(headers);

        assertThat(mapped)
                .as("底层自算的传输层头不交给守卫（否则合法请求会被误拒）")
                .doesNotContainKeys(
                        HttpHeaders.HOST,
                        HttpHeaders.CONTENT_LENGTH,
                        HttpHeaders.CONNECTION,
                        HttpHeaders.TRANSFER_ENCODING)
                .containsEntry("Accept", "application/json")
                .containsEntry("Authorization", "Bearer sk-test")
                .containsEntry(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        assertThat(mapped).as("凭据类头不剥离，仍由守卫按 F09 规则拒绝").containsEntry(HttpHeaders.COOKIE, "session=secret");
    }

    @Test
    void nullHeadersMapToEmptyRequestHeaders() {
        assertThat(GuardedExternalHttpTransport.toGuardHeaders(null)).isEmpty();
    }

    @Test
    void multiValueHeadersAreJoinedAndEmptyValuesSurvive() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Trace", "a");
        headers.add("X-Trace", "b");
        // addAll 才能表达"存在但无取值"的头（add 的可变参数重载只会拼字符串）
        headers.addAll("X-Empty", List.of());

        var mapped = GuardedExternalHttpTransport.toGuardHeaders(headers);

        assertThat(mapped).containsEntry("X-Trace", "a, b").containsEntry("X-Empty", "");
    }

    @Test
    void denialReasonsMapToStableModelReasons() {
        assertThat(GuardedExternalHttpTransport.toModelReason(denial(ExternalHttpException.Reason.TARGET_NOT_ALLOWED)))
                .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED);
        assertThat(GuardedExternalHttpTransport.toModelReason(
                        denial(ExternalHttpException.Reason.PRIVATE_TARGET_DENIED)))
                .as("私网目标未被批准同样是稳定的目标不允许")
                .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED);
        assertThat(GuardedExternalHttpTransport.toModelReason(denial(ExternalHttpException.Reason.INVALID_REQUEST)))
                .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED);
        assertThat(GuardedExternalHttpTransport.toModelReason(denial(ExternalHttpException.Reason.TIMEOUT)))
                .as("超时仍是可重试的稳定原因")
                .isEqualTo(ModelException.Reason.TIMEOUT);
        assertThat(GuardedExternalHttpTransport.toModelReason(denial(ExternalHttpException.Reason.CONNECT_FAILED)))
                .isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
        assertThat(GuardedExternalHttpTransport.toModelReason(denial(ExternalHttpException.Reason.RESPONSE_TOO_LARGE)))
                .isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
    }

    @Test
    void denialIsFoundThroughVendorWrappingAndNonDenialIsIgnored() {
        RuntimeException wrapped = new IllegalStateException("vendor wrapper");
        wrapped.initCause(denial(ExternalHttpException.Reason.PRIVATE_TARGET_DENIED));

        assertThat(GuardedExternalHttpTransport.toModelReason(wrapped))
                .as("厂商把请求期异常包进自己的异常里也要能识别")
                .isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED);
        assertThat(GuardedExternalHttpTransport.toModelReason(new IllegalStateException("普通上游故障")))
                .as("不是守卫拒绝时不改写原有映射")
                .isNull();
        assertThat(GuardedExternalHttpTransport.toModelReason(null)).isNull();
    }

    @Test
    void deeplyNestedCauseChainTerminatesWithoutDenial() {
        // JDK 不允许自引用 cause，这里覆盖"因果链超深"的有界查找：链够长也必须收敛为"非守卫拒绝"
        Throwable current = null;
        for (int depth = 0; depth < 40; depth++) {
            current = new IllegalStateException("layer-" + depth, current);
        }

        assertThat(GuardedExternalHttpTransport.toModelReason(current)).isNull();
    }

    @Test
    void denialMessageStaysStableAndLeaksNothing() {
        ModelException mapped = SpringAiModelClient.mapFailure(
                new ExternalHttpException(ExternalHttpException.Reason.PRIVATE_TARGET_DENIED, "目标解析到私网或环回地址"));

        assertThat(mapped.getReason()).isEqualTo(ModelException.Reason.TARGET_NOT_ALLOWED);
        assertThat(mapped.getMessage()).as("不区分主机不在清单与私网未批准，也不回带目标地址").isEqualTo("出站请求被受控边界拒绝");
        assertThat(mapped.getMessage()).doesNotContain("私网").doesNotContain("127.0.0.1");
        assertThat(SpringAiModelClient.mapFailure(new TimeoutFailure()).getReason())
                .as("非守卫超时的既有映射不变")
                .isEqualTo(ModelException.Reason.TIMEOUT);
        assertThat(SpringAiModelClient.mapFailure(new IllegalStateException("boom"))
                        .getReason())
                .isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
    }

    @Test
    void guardTimeoutUsesTheSameStableWordingAsVendorTimeout() {
        ModelException mapped = SpringAiModelClient.mapFailure(
                new ExternalHttpException(ExternalHttpException.Reason.TIMEOUT, "出站请求超时"));

        assertThat(mapped.getReason()).isEqualTo(ModelException.Reason.TIMEOUT);
        assertThat(mapped.getMessage()).isEqualTo("模型调用超时");
    }

    @Test
    void responseViewKeepsStatusHeadersAndBody() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        var springResponse = executeAgainst(201, body.length, body);

        assertThat(springResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(201));
        assertThat(springResponse.getStatusText()).isEqualTo("Created");
        assertThat(springResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(springResponse.getBody().readAllBytes()).isEqualTo(body);
        springResponse.close();
    }

    @Test
    void responseViewReportsEmptyTextForUnknownStatusAndNullBody() throws Exception {
        var springResponse = executeAgainst(599, -1L, null);

        assertThat(springResponse.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(599));
        assertThat(springResponse.getStatusText()).as("未知状态码没有标准文案时给空串，不抛异常").isEmpty();
        assertThat(springResponse.getBody().readAllBytes()).isEmpty();
    }

    /**
     * 响应式请求体落地目标：请求形态可读、单块与分块写入都能收集、无请求体时给 null、
     * 提交与落地是无副作用的空操作（响应式通道按块写，不走"先落一整块再提交"的语义）。
     */
    @Test
    void reactiveBufferingRequestCollectsBodyAndExposesRequestShape() throws Exception {
        URI uri = URI.create("https://api.example.com/v1/x");
        var request = new GuardedExchangeFunction.BufferingReactiveRequest(HttpMethod.POST, uri);

        assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(request.getURI()).isEqualTo(uri);
        assertThat(request.<Object>getNativeRequest()).isNull();
        assertThat(request.bufferFactory()).isNotNull();
        assertThat(request.getCookies()).as("出站请求不带宿主 Cookie").isEmpty();
        assertThat(request.body()).as("还没有写入请求体").isNull();

        byte[] chunk = "{}".getBytes(StandardCharsets.UTF_8);
        request.writeWith(Mono.just(request.bufferFactory().wrap(chunk))).block();
        assertThat(request.body()).isEqualTo(chunk);

        // 分块写入路径：BodyInserter 可能给出多个 Publisher
        var chunked = new GuardedExchangeFunction.BufferingReactiveRequest(HttpMethod.POST, uri);
        List<Publisher<DataBuffer>> chunks = List.of(
                Mono.just(chunked.bufferFactory().wrap("a".getBytes(StandardCharsets.UTF_8))),
                Mono.just(chunked.bufferFactory().wrap("b".getBytes(StandardCharsets.UTF_8))));
        chunked.writeAndFlushWith(Flux.fromIterable(chunks)).block();
        assertThat(new String(chunked.body(), StandardCharsets.UTF_8)).isEqualTo("ab");

        // 提交/落地/头与 Cookie 落地都是空操作：头由 Spring 直接写入 headers
        request.setComplete().block();
        request.doCommit().block();
        request.applyHeaders();
        request.applyCookies();
        assertThat(request.getHeaders()).isEmpty();
    }

    @Test
    void guardTransportFailuresReuseTheUpstreamFailedWording() {
        for (ExternalHttpException.Reason reason :
                List.of(ExternalHttpException.Reason.CONNECT_FAILED, ExternalHttpException.Reason.RESPONSE_TOO_LARGE)) {
            ModelException mapped = SpringAiModelClient.mapFailure(new ExternalHttpException(reason, "守卫失败"));

            assertThat(mapped.getReason()).isEqualTo(ModelException.Reason.UPSTREAM_FAILED);
            assertThat(mapped.getMessage()).as("传输层失败沿用既有文案，不新增对外语义").isEqualTo("模型调用失败");
            assertThat(SpringAiModelClient.isRetryable(mapped.getReason()))
                    .as("传输层失败仍按既有规则视为可重试")
                    .isTrue();
        }
    }

    @Test
    void transportRejectsNullExternalHttpClient() {
        assertThatThrownBy(() -> new GuardedExternalHttpTransport(null)).isInstanceOf(NullPointerException.class);
    }

    /** 用固定响应桩驱动一次适配器请求，返回 Spring 侧响应视图。 */
    private static org.springframework.http.client.ClientHttpResponse executeAgainst(
            int status, long declared, byte[] body) throws Exception {
        // ExternalHttpResponse 的响应头契约是 Map<String,String>（守卫侧的多值头已合并）
        var stub = new StubExternalHttpClient(new ExternalHttpResponse(
                status, Map.of("Content-Type", MediaType.APPLICATION_JSON_VALUE), body, declared));
        var request = new GuardedClientHttpRequestFactory(stub)
                .createRequest(URI.create("https://api.example.com/v1/x"), HttpMethod.POST);
        request.getHeaders().set("Authorization", "Bearer sk-test");
        request.getBody().write("{}".getBytes(StandardCharsets.UTF_8));
        return request.execute();
    }

    private static ExternalHttpException denial(ExternalHttpException.Reason reason) {
        return new ExternalHttpException(reason, "守卫拒绝");
    }

    /** 类名含 timeout 的非受检异常：验证非守卫超时仍按既有规则映射。 */
    private static final class TimeoutFailure extends RuntimeException {

        TimeoutFailure() {
            super("vendor timeout");
        }
    }

    /** 只回放固定响应的守卫桩：用于断言响应视图，不做任何网络动作。 */
    private static final class StubExternalHttpClient implements ExternalHttpClient {

        private final ExternalHttpResponse response;

        StubExternalHttpClient(ExternalHttpResponse response) {
            this.response = response;
        }

        @Override
        public ExternalHttpResponse execute(ExternalHttpRequest request) {
            return response;
        }

        @Override
        public CompletableFuture<ExternalHttpResponse> executeAsync(ExternalHttpRequest request) {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {
            // 无资源可释放
        }
    }
}
