package com.basicframework.framework.web.core.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.util.Assert;

/**
 *  Request Body 缓存 Wrapper
 *
 */
public class CacheRequestBodyWrapper extends HttpServletRequestWrapper {

    /**
     * 缓存的内容
     */
    private final byte[] body;

    public CacheRequestBodyWrapper(HttpServletRequest request, int maxBodyBytes) throws IOException {
        super(request);
        Assert.isTrue(maxBodyBytes > 0 && maxBodyBytes < Integer.MAX_VALUE, "请求体缓存上限无效");
        byte[] candidate = request.getInputStream().readNBytes(maxBodyBytes + 1);
        if (candidate.length > maxBodyBytes) {
            throw new RequestBodyTooLargeException();
        }
        body = candidate;
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(this.getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
        // 返回 ServletInputStream
        return new ServletInputStream() {

            @Override
            public int read() {
                return inputStream.read();
            }

            @Override
            public boolean isFinished() {
                return inputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                if (readListener == null) {
                    throw new IllegalArgumentException("ReadListener 不能为空");
                }
                try {
                    if (!isFinished()) {
                        readListener.onDataAvailable();
                    }
                    if (isFinished()) {
                        readListener.onAllDataRead();
                    }
                } catch (IOException exception) {
                    readListener.onError(exception);
                }
            }

            @Override
            public int available() {
                return inputStream.available();
            }
        };
    }
}
