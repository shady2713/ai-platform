package com.basicframework.framework.web.core.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CacheRequestBodyWrapperTest {

    @Test
    void body_canBeReadRepeatedlyWithAccurateStreamState() throws Exception {
        MockHttpServletRequest request = jsonRequest("你好");
        CacheRequestBodyWrapper wrapper = new CacheRequestBodyWrapper(request, 64);

        ServletInputStream first = wrapper.getInputStream();
        assertThat(first.isReady()).isTrue();
        assertThat(first.isFinished()).isFalse();
        assertThat(first.available()).isEqualTo("你好".getBytes(StandardCharsets.UTF_8).length);
        assertThat(first.readAllBytes()).isEqualTo("你好".getBytes(StandardCharsets.UTF_8));
        assertThat(first.isFinished()).isTrue();
        assertThat(first.available()).isZero();
        assertThat(wrapper.getReader().readLine()).isEqualTo("你好");
        assertThat(wrapper.getContentLength()).isEqualTo(6);
        assertThat(wrapper.getContentLengthLong()).isEqualTo(6L);
    }

    @Test
    void readListener_receivesDataAndCompletionCallbacks() throws Exception {
        CacheRequestBodyWrapper wrapper = new CacheRequestBodyWrapper(jsonRequest("body"), 16);
        ServletInputStream stream = wrapper.getInputStream();
        AtomicBoolean dataAvailable = new AtomicBoolean();
        AtomicBoolean allDataRead = new AtomicBoolean();

        stream.setReadListener(new ReadListener() {
            @Override
            public void onDataAvailable() throws IOException {
                dataAvailable.set(true);
                stream.readAllBytes();
            }

            @Override
            public void onAllDataRead() {
                allDataRead.set(true);
            }

            @Override
            public void onError(Throwable throwable) {
                throw new AssertionError(throwable);
            }
        });

        assertThat(dataAvailable).isTrue();
        assertThat(allDataRead).isTrue();
        assertThatThrownBy(() -> wrapper.getInputStream().setReadListener(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsOverflowAndInvalidLimit() {
        assertThatThrownBy(() -> new CacheRequestBodyWrapper(jsonRequest("12345"), 4))
                .isInstanceOf(RequestBodyTooLargeException.class);
        assertThatThrownBy(() -> new CacheRequestBodyWrapper(jsonRequest(""), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缓存上限");
    }

    private static MockHttpServletRequest jsonRequest(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
