package com.basicframework.framework.ai.provider.springai;

import com.basicframework.framework.ai.core.http.ExternalHttpClient;
import com.basicframework.framework.ai.core.http.ExternalHttpRequest;
import com.basicframework.framework.ai.core.http.ExternalHttpResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 阻塞传输适配器：把 Spring 的 {@link ClientHttpRequestFactory} 桥到 F09 受控出站边界（M07）。
 *
 * <p>覆盖走 {@code RestClient} 的四条通道：聊天同步、嵌入、转写、语音合成。
 * 请求体在内存里缓冲（模型调用体量都是 JSON/小 multipart），执行时一次性交给守卫；
 * 守卫抛出的 {@link com.basicframework.framework.ai.core.http.ExternalHttpException} 原样上抛，
 * 不包成 IOException —— 这样"被出站策略拒绝"不会被 Spring 归类成网络故障，稳定错误码才立得住。
 */
final class GuardedClientHttpRequestFactory implements ClientHttpRequestFactory {

    private final ExternalHttpClient httpClient;

    GuardedClientHttpRequestFactory(ExternalHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
        return new GuardedClientHttpRequest(httpClient, uri, httpMethod);
    }

    /** 单次请求：先缓冲请求体，执行时交给守卫，返回由守卫响应构造的 Spring 响应视图。 */
    private static final class GuardedClientHttpRequest implements ClientHttpRequest {

        private final ExternalHttpClient httpClient;

        private final URI uri;

        private final HttpMethod method;

        private final HttpHeaders headers = new HttpHeaders();

        /** 必须可变：RestClient 会往里 putAll 请求属性。 */
        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        GuardedClientHttpRequest(ExternalHttpClient httpClient, URI uri, HttpMethod method) {
            this.httpClient = httpClient;
            this.uri = uri;
            this.method = method;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public OutputStream getBody() {
            return body;
        }

        @Override
        public ClientHttpResponse execute() {
            ExternalHttpResponse response = httpClient.execute(new ExternalHttpRequest(
                    method.name(),
                    uri.toString(),
                    GuardedExternalHttpTransport.toGuardHeaders(headers),
                    body.size() == 0 ? null : body.toByteArray(),
                    null));
            return new GuardedClientHttpResponse(response);
        }
    }

    /** 守卫响应的 Spring 视图：状态、必要响应头与受大小上限约束的响应体原样透出。 */
    private static final class GuardedClientHttpResponse implements ClientHttpResponse {

        private final ExternalHttpResponse response;

        private final HttpHeaders headers;

        GuardedClientHttpResponse(ExternalHttpResponse response) {
            this.response = response;
            HttpHeaders mapped = new HttpHeaders();
            response.headers().forEach(mapped::add);
            this.headers = HttpHeaders.readOnlyHttpHeaders(mapped);
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatusCode.valueOf(response.status());
        }

        @Override
        public String getStatusText() {
            HttpStatus resolved = HttpStatus.resolve(response.status());
            return resolved == null ? "" : resolved.getReasonPhrase();
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public ByteArrayInputStream getBody() {
            return new ByteArrayInputStream(response.body() == null ? new byte[0] : response.body());
        }

        @Override
        public void close() {
            // 响应体已完全缓冲，没有需要释放的连接资源
        }
    }
}
