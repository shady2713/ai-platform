package com.basicframework.framework.common.util.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 客户端 IP 解析安全契约：默认不信任任何代理头；仅当直接对端命中可信代理列表时才采信
 * X-Forwarded-For / X-Real-IP，且代理头值必须是合法 IP 字形。
 */
class ClientIpResolverTest {

    @Test
    void resolve_withoutTrustedProxy_ignoresAllProxyHeaders() {
        new ClientIpResolver(List.of());
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Real-IP", "198.51.100.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.2");
    }

    @Test
    void resolve_withTrustedProxyExactMatch_usesFirstForwardedHop() {
        new ClientIpResolver(List.of("10.0.0.2"));
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void resolve_withTrustedProxyCidrMatch_fallsBackToRealIpHeader() {
        new ClientIpResolver(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = request("10.20.30.40");
        request.addHeader("X-Real-IP", "198.51.100.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void resolve_withUntrustedRemoteAddr_ignoresProxyHeaders() {
        new ClientIpResolver(List.of("10.0.0.2"));
        MockHttpServletRequest request = request("192.168.1.1");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("192.168.1.1");
    }

    @Test
    void resolve_withTrustedProxyAndMalformedHeader_fallsBackToRemoteAddr() {
        new ClientIpResolver(List.of("10.0.0.2"));
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "not-an-ip;drop table users--");
        request.addHeader("X-Real-IP", "198.51.100.9");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.2");
    }

    @Test
    void resolve_withTrustedProxyAndBlankHeaders_fallsBackToRemoteAddr() {
        new ClientIpResolver(List.of("10.0.0.2"));
        MockHttpServletRequest request = request("10.0.0.2");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.2");
    }

    @Test
    void configure_rejectsInvalidProxyEntriesAtStartup() {
        assertThatThrownBy(() -> new ClientIpResolver(List.of("not-an-ip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxies");
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/33")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxies");
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxies");
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/1a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxies");
        assertThatThrownBy(() -> new ClientIpResolver(List.of("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxies");
    }

    @Test
    void configure_acceptsIpv4Ipv6AndCidrEntries() {
        new ClientIpResolver(List.of("127.0.0.1", "::1", "10.0.0.0/24"));
        MockHttpServletRequest request = request("::1");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("::1");
    }

    @Test
    void configure_acceptsNullListAsNoTrustedProxy() {
        new ClientIpResolver(null);
        MockHttpServletRequest request = request("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.2");
    }

    @Test
    void resolve_withCidrTrustedProxyAndIpv4RemoteAddr_UsesForwardedHeader() {
        new ClientIpResolver(List.of("192.168.0.0/16"));
        MockHttpServletRequest request = request("192.168.10.20");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void resolve_withCidrTrustedProxyOutOfRange_IgnoresForwardedHeader() {
        new ClientIpResolver(List.of("192.168.0.0/16", "10.0.0.2"));
        MockHttpServletRequest request = request("198.51.100.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(ClientIpResolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void resolve_withNullRemoteAddr_ReturnsNullWithoutHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(null);
        new ClientIpResolver(List.of("10.0.0.0/8"));

        assertThat(ClientIpResolver.resolve(request)).isNull();
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
