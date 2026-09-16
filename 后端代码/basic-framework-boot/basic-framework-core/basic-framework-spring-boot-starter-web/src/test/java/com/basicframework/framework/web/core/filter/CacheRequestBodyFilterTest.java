package com.basicframework.framework.web.core.filter;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CacheRequestBodyFilterTest {

    private final CacheRequestBodyFilter filter = new CacheRequestBodyFilter(8);

    @Test
    void jsonRequest_isWrappedAndRemainsRepeatable() throws Exception {
        MockHttpServletRequest request = request("/api", "12345678", MediaType.APPLICATION_JSON_VALUE);
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilterInternal(request, new MockHttpServletResponse(), (wrapped, response) -> {
            invoked.set(true);
            assertThat(wrapped).isInstanceOf(CacheRequestBodyWrapper.class);
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo("12345678".getBytes(StandardCharsets.UTF_8));
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo("12345678".getBytes(StandardCharsets.UTF_8));
        });

        assertThat(invoked).isTrue();
    }

    @Test
    void knownAndChunkedOverflow_return413WithoutCallingBusinessChain() throws Exception {
        MockHttpServletRequest known = request("/api", "123456789", MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse knownResponse = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilterInternal(known, knownResponse, (request, response) -> invoked.set(true));

        MockHttpServletRequest chunkedBody = request("/api", "123456789", MediaType.APPLICATION_JSON_VALUE);
        HttpServletRequestWrapper chunked = new HttpServletRequestWrapper(chunkedBody) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        MockHttpServletResponse chunkedResponse = new MockHttpServletResponse();
        filter.doFilterInternal(chunked, chunkedResponse, (request, response) -> invoked.set(true));

        assertThat(invoked).isFalse();
        assertThat(knownResponse.getStatus()).isEqualTo(413);
        assertThat(chunkedResponse.getStatus()).isEqualTo(413);
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(
                        MediaType.parseMediaType(knownResponse.getContentType())))
                .isTrue();
        assertThat(knownResponse.getContentAsString()).contains("\"code\":413", "请求内容过大");
        assertThat(chunkedResponse.getContentAsString()).contains("\"code\":413", "请求内容过大");
    }

    @Test
    void shouldNotFilter_managementOrNonJsonRequests() {
        assertThat(filter.shouldNotFilter(request("/admin/status", "{}", MediaType.APPLICATION_JSON_VALUE)))
                .isTrue();
        assertThat(filter.shouldNotFilter(request("/actuator/health", "{}", MediaType.APPLICATION_JSON_VALUE)))
                .isTrue();
        assertThat(filter.shouldNotFilter(request("/api", "plain", MediaType.TEXT_PLAIN_VALUE)))
                .isTrue();
        assertThat(filter.shouldNotFilter(request("/api", "{}", MediaType.APPLICATION_JSON_VALUE)))
                .isFalse();
    }

    private static MockHttpServletRequest request(String uri, String body, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
