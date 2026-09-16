package com.basicframework.framework.xss.core.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.xss.core.clean.JsoupXssCleaner;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class XssRequestWrapperTest {

    @Test
    void parameterAccess_cleansValuesWithoutMutatingUnderlyingRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String unsafe = "<script>alert(1)</script><b>safe</b>";
        request.setParameter("value", unsafe, "plain");
        XssRequestWrapper wrapper = new XssRequestWrapper(request, new JsoupXssCleaner());

        Map<String, String[]> cleaned = wrapper.getParameterMap();

        assertThat(cleaned.get("value")).containsExactly("<b>safe</b>", "plain");
        assertThat(wrapper.getParameterValues("value")).containsExactly("<b>safe</b>", "plain");
        assertThat(wrapper.getParameter("value")).isEqualTo("<b>safe</b>");
        assertThat(request.getParameterValues("value")).containsExactly(unsafe, "plain");
        assertThat(wrapper.getParameter("missing")).isNull();
        assertThat(wrapper.getParameterValues("missing")).isNull();
    }

    @Test
    void frameworkMetadataAndHeaders_areNeverRewritten() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer <opaque-token>");
        request.setAttribute("framework.state", "<internal>");
        request.setQueryString("redirect=%3Ctrusted-state%3E");
        XssRequestWrapper wrapper = new XssRequestWrapper(request, new JsoupXssCleaner());

        assertThat(wrapper.getHeader("Authorization")).isEqualTo("Bearer <opaque-token>");
        assertThat(wrapper.getAttribute("framework.state")).isEqualTo("<internal>");
        assertThat(wrapper.getQueryString()).isEqualTo("redirect=%3Ctrusted-state%3E");
    }
}
