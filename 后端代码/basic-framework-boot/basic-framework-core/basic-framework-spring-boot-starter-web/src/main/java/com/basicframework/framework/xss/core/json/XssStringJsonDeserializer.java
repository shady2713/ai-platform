package com.basicframework.framework.xss.core.json;

import com.basicframework.framework.common.util.servlet.ServletUtils;
import com.basicframework.framework.xss.config.XssProperties;
import com.basicframework.framework.xss.core.clean.XssCleaner;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.AllArgsConstructor;
import org.springframework.util.PathMatcher;

/**
 * XSS 过滤 jackson 反序列化器。
 * 在反序列化的过程中，会对字符串进行 XSS 过滤。
 *
 */
@AllArgsConstructor
public class XssStringJsonDeserializer extends StringDeserializer {

    /**
     * 属性
     */
    private final XssProperties properties;
    /**
     * 路径匹配器
     */
    private final PathMatcher pathMatcher;

    private final XssCleaner xssCleaner;

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (!p.hasToken(JsonToken.VALUE_STRING)) {
            return (String) ctxt.handleUnexpectedToken(_valueClass, p);
        }
        String value = p.getText();
        HttpServletRequest request = ServletUtils.getRequest();
        if (request != null) {
            String uri = request.getRequestURI();
            if (properties.getExcludeUrls().stream().anyMatch(excludeUrl -> pathMatcher.match(excludeUrl, uri))) {
                return value;
            }
        }
        return xssCleaner.clean(value);
    }
}
