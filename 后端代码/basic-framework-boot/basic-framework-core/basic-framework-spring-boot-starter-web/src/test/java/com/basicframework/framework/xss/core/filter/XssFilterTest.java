package com.basicframework.framework.xss.core.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.xss.config.XssProperties;
import com.basicframework.framework.xss.core.clean.JsoupXssCleaner;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.AntPathMatcher;

class XssFilterTest {

    @Test
    void filter_wrapsEnabledNonExcludedRequest() throws Exception {
        XssFilter filter = filter(new XssProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items");
        AtomicReference<Object> forwardedRequest = new AtomicReference<>();

        filter.doFilterInternal(request, new MockHttpServletResponse(), (req, response) -> forwardedRequest.set(req));

        assertThat(forwardedRequest.get()).isInstanceOf(XssRequestWrapper.class);
    }

    @Test
    void shouldNotFilter_whenDisabledOrExplicitlyExcluded() {
        XssProperties disabled = new XssProperties();
        disabled.setEnable(false);
        XssProperties excluded = new XssProperties();
        excluded.setExcludeUrls(List.of("/actuator/**"));

        assertThat(filter(disabled).shouldNotFilter(new MockHttpServletRequest("GET", "/api/items")))
                .isTrue();
        assertThat(filter(excluded).shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health")))
                .isTrue();
        assertThat(filter(excluded).shouldNotFilter(new MockHttpServletRequest("GET", "/api/items")))
                .isFalse();
    }

    private static XssFilter filter(XssProperties properties) {
        return new XssFilter(properties, new AntPathMatcher(), new JsoupXssCleaner());
    }
}
