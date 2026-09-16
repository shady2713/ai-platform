package com.basicframework.framework.common.util.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ServletUtilsTest {

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void currentRequestAccessors_handleBoundAndMissingRequests() {
        assertThat(ServletUtils.getRequest()).isNull();
        assertThat(ServletUtils.getUserAgent()).isNull();
        assertThat(ServletUtils.getClientIP()).isNull();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "test-agent");
        request.setRemoteAddr("192.0.2.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(ServletUtils.getRequest()).isSameAs(request);
        assertThat(ServletUtils.getUserAgent()).isEqualTo("test-agent");
        assertThat(ServletUtils.getClientIP()).isEqualTo("192.0.2.10");
    }

    @Test
    void requestAccessors_readJsonBodyParametersAndFallbackUserAgent() {
        MockHttpServletRequest jsonRequest = jsonRequest("{\"name\":\"shady\"}");
        jsonRequest.addParameter("page", "1");

        assertThat(ServletUtils.isJsonRequest(jsonRequest)).isTrue();
        assertThat(ServletUtils.getBody(jsonRequest)).isEqualTo("{\"name\":\"shady\"}");
        assertThat(ServletUtils.getParamMap(jsonRequest)).containsEntry("page", "1");
        assertThat(ServletUtils.getUserAgent(jsonRequest)).isEmpty();
        assertThat(ServletUtils.getClientIP(jsonRequest)).isEqualTo("127.0.0.1");

        MockHttpServletRequest bytesRequest = jsonRequest("payload");
        assertThat(ServletUtils.getBodyBytes(bytesRequest)).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));

        MockHttpServletRequest formRequest = new MockHttpServletRequest();
        formRequest.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        assertThat(ServletUtils.isJsonRequest(formRequest)).isFalse();
        assertThat(ServletUtils.getBody(formRequest)).isNull();
        assertThat(ServletUtils.getBodyBytes(formRequest)).isNull();
    }

    @Test
    void writeJson_setsStatusContentTypeAndBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ServletUtils.writeJSON(response, 418, Map.of("result", "ok"));

        assertThat(response.getStatus()).isEqualTo(418);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).isEqualTo("{\"result\":\"ok\"}");
    }

    private static MockHttpServletRequest jsonRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
