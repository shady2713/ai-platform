package com.basicframework.framework.common.util.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpUtilsTest {

    @Test
    void encodeAndDecodeUtf8_roundTripUnicodeAndReservedCharacters() {
        String value = "中文 file+a&b";

        assertThat(HttpUtils.decodeUtf8(HttpUtils.encodeUtf8(value))).isEqualTo(value);
    }

    @Test
    void removeUrlQuery_stripsQueryAndFragmentOnlyWhenQueryExists() {
        assertThat(HttpUtils.removeUrlQuery("https://example.test/path?token=secret#section"))
                .isEqualTo("https://example.test/path");
        assertThat(HttpUtils.removeUrlQuery("https://example.test/path#section"))
                .isEqualTo("https://example.test/path#section");
    }

    @Test
    void post_sendsHeadersAndBodyAndReturnsResponse() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/sms", exchange -> {
            try (exchange) {
                method.set(exchange.getRequestMethod());
                signature.set(exchange.getRequestHeaders().getFirst("X-Signature"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "accepted".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();

        try {
            String response = HttpUtils.post(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/sms",
                    Map.of("X-Signature", "signed"),
                    "{\"code\":1234}");

            assertThat(response).isEqualTo("accepted");
            assertThat(method).hasValue("POST");
            assertThat(signature).hasValue("signed");
            assertThat(requestBody).hasValue("{\"code\":1234}");
        } finally {
            server.stop(0);
        }
    }
}
