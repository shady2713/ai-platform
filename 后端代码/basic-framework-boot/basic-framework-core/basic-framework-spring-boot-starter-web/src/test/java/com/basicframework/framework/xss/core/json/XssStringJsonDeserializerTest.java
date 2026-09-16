package com.basicframework.framework.xss.core.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.xss.config.XssProperties;
import com.basicframework.framework.xss.core.clean.JsoupXssCleaner;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class XssStringJsonDeserializerTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void deserialize_cleansStringFieldsByDefault() throws Exception {
        TextPayload payload = mapper(new XssProperties())
                .readValue("{\"value\":\"<script>x</script><b>safe</b>\"}", TextPayload.class);

        assertThat(payload.value()).isEqualTo("<b>safe</b>");
    }

    @Test
    void deserialize_explicitExcludedEndpointPreservesPayload() throws Exception {
        XssProperties properties = new XssProperties();
        properties.setExcludeUrls(List.of("/rich-text/**"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rich-text/articles");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        TextPayload payload =
                mapper(properties).readValue("{\"value\":\"<script>x</script><b>safe</b>\"}", TextPayload.class);

        assertThat(payload.value()).isEqualTo("<script>x</script><b>safe</b>");
    }

    @Test
    void deserialize_rejectsNonStringJsonTokensInsteadOfCoercingThem() {
        ObjectMapper objectMapper = mapper(new XssProperties());

        for (String json : List.of("{\"value\":123}", "{\"value\":[\"text\"]}", "{\"value\":{\"x\":1}}")) {
            assertThatThrownBy(() -> objectMapper.readValue(json, TextPayload.class))
                    .as("payload %s must keep its declared JSON type", json)
                    .isInstanceOf(MismatchedInputException.class);
        }
    }

    @Test
    void deserialize_exclusionOnlySkipsCleaningAndNeverSkipsTypeValidation() {
        XssProperties properties = new XssProperties();
        properties.setExcludeUrls(List.of("/rich-text/**"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rich-text/articles");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThatThrownBy(() -> mapper(properties).readValue("{\"value\":123}", TextPayload.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void deserialize_delegatesUnexpectedTokensToJacksonContext() throws Exception {
        JsonParser parser = mock(JsonParser.class);
        DeserializationContext context = mock(DeserializationContext.class);
        when(parser.hasToken(JsonToken.VALUE_STRING)).thenReturn(false);
        when(context.handleUnexpectedToken(String.class, parser)).thenReturn("fallback");

        String value = new XssStringJsonDeserializer(new XssProperties(), new AntPathMatcher(), new JsoupXssCleaner())
                .deserialize(parser, context);

        assertThat(value).isEqualTo("fallback");
    }

    private static ObjectMapper mapper(XssProperties properties) {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(
                String.class, new XssStringJsonDeserializer(properties, new AntPathMatcher(), new JsoupXssCleaner()));
        return new ObjectMapper().registerModule(module);
    }

    private record TextPayload(String value) {}
}
