package com.basicframework.framework.common.util.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class JsonUtilsTest {

    private final ObjectMapper originalObjectMapper = JsonUtils.getObjectMapper();

    @AfterEach
    void restoreObjectMapper() {
        JsonUtils.init(originalObjectMapper);
    }

    @Test
    void serializationAndTreeParsing_preserveStructuredValues() {
        Sample sample = new Sample().setId(7L).setName("shady");

        String json = JsonUtils.toJsonString(sample);
        byte[] bytes = JsonUtils.toJsonByte(sample);
        String prettyJson = JsonUtils.toJsonPrettyString(sample);
        JsonNode tree = JsonUtils.parseTree(json);

        assertThat(json).contains("\"id\":7", "\"name\":\"shady\"");
        assertThat(JsonUtils.parseTree(bytes).get("id").asLong()).isEqualTo(7L);
        assertThat(prettyJson).contains(System.lineSeparator());
        assertThat(tree.get("name").asText()).isEqualTo("shady");
    }

    @Test
    void serializationFailuresNeverReturnPartialJson() {
        Object recursive = new Object() {
            public Object getSelf() {
                return this;
            }
        };
        assertThatThrownBy(() -> JsonUtils.toJsonString(recursive))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> JsonUtils.toJsonByte(recursive))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(() -> JsonUtils.toJsonPrettyString(recursive))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void objectParsing_supportsClassPathTypeBytesAndTypeReference() {
        String objectJson = "{\"payload\":{\"id\":8,\"name\":\"nested\"}}";
        String listJson = "[{\"id\":9,\"name\":\"list\"}]";
        Type listType = new TypeReference<List<Sample>>() {}.getType();

        assertThat(JsonUtils.parseObject("{\"id\":7}", Sample.class).getId()).isEqualTo(7L);
        assertThat(JsonUtils.parseObject(objectJson, "payload", Sample.class).getName())
                .isEqualTo("nested");
        assertThat(JsonUtils.<List<Sample>>parseObject(listJson, listType)
                        .get(0)
                        .getId())
                .isEqualTo(9L);
        assertThat(JsonUtils.<List<Sample>>parseObject(listJson.getBytes(), listType)
                        .get(0)
                        .getName())
                .isEqualTo("list");
        assertThat(JsonUtils.parseObject(listJson, new TypeReference<List<Sample>>() {})
                        .get(0)
                        .getId())
                .isEqualTo(9L);
        assertThat(JsonUtils.parseObject("{\"id\":10}".getBytes(), Sample.class).getId())
                .isEqualTo(10L);
    }

    @Test
    void arrayAndConversionHelpers_handleValuesAndEmptyInputs() {
        String wrapped = "{\"items\":[{\"id\":11}]}";

        assertThat(JsonUtils.parseArray("[{\"id\":12}]", Sample.class))
                .extracting(Sample::getId)
                .containsExactly(12L);
        assertThat(JsonUtils.parseArray(wrapped, "items", Sample.class))
                .extracting(Sample::getId)
                .containsExactly(11L);
        assertThat(JsonUtils.convertObject(Map.of("id", 13L), Sample.class).getId())
                .isEqualTo(13L);
        assertThat(JsonUtils.convertObject(List.of(Map.of("id", 14L)), new TypeReference<List<Sample>>() {}))
                .extracting(Sample::getId)
                .containsExactly(14L);
        assertThat(JsonUtils.parseArray("", Sample.class)).isEmpty();
        assertThat(JsonUtils.convertObject(null, Sample.class)).isNull();
    }

    @Test
    void invalidAndQuietParsing_followExplicitFailureContracts() {
        assertThat(JsonUtils.parseObject("", Sample.class)).isNull();
        assertThat(JsonUtils.parseObject("", "payload", Sample.class)).isNull();
        assertThat(JsonUtils.<Sample>parseObject("", (Type) Sample.class)).isNull();
        assertThat(JsonUtils.<Sample>parseObject(new byte[0], (Type) Sample.class))
                .isNull();
        assertThat(JsonUtils.parseObject(new byte[0], Sample.class)).isNull();
        assertThat(JsonUtils.<Sample>parseObject("", new TypeReference<Sample>() {}))
                .isNull();
        assertThat(JsonUtils.parseObject2("", Sample.class)).isNull();
        assertThat(JsonUtils.parseArray("", "items", Sample.class)).isNull();
        assertThat(JsonUtils.convertObject(null, new TypeReference<Sample>() {}))
                .isNull();
        assertThat(JsonUtils.parseObjectQuietly("not-json", new TypeReference<Sample>() {}))
                .isNull();
        assertThat(JsonUtils.parseObjectQuietly("{\"id\":18}", new TypeReference<Sample>() {})
                        .getId())
                .isEqualTo(18L);
        assertThatThrownBy(() -> JsonUtils.parseObject("not-json", Sample.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseObject("not-json", "payload", Sample.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseObject("not-json", (Type) Sample.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseObject("not-json".getBytes(), (Type) Sample.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseObject("not-json".getBytes(), Sample.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseObject("not-json", new TypeReference<Sample>() {}))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseArray("not-json", Sample.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseArray("not-json", "items", Sample.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseTree("not-json")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonUtils.parseTree("not-json".getBytes())).isInstanceOf(RuntimeException.class);
        assertThat(JsonUtils.isJson("[]")).isTrue();
        assertThat(JsonUtils.isJsonObject("{}")).isTrue();
    }

    @Test
    void invalidParsing_doesNotExposeInputThroughLogsOrTheExceptionGraph() {
        String sensitiveInput = "unquoted-private-token-" + UUID.randomUUID();
        Logger logger = (Logger) LoggerFactory.getLogger(JsonUtils.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        Throwable failure;
        try {
            failure = catchThrowable(() -> JsonUtils.parseObject(sensitiveInput, Sample.class));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasNoCause()
                .hasMessageNotContaining(sensitiveInput);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allMatch(message -> !message.contains(sensitiveInput))
                .anyMatch(message -> message.contains("input=text(length="))
                .anyMatch(message -> message.contains("exceptionName="));
    }

    @Test
    void initAndParseObject2_useConfiguredMapperAndTrustedConcreteType() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonUtils.init(objectMapper);
        Sample same = new Sample().setId(16L);

        assertThat(JsonUtils.getObjectMapper()).isSameAs(objectMapper);
        assertThat(JsonUtils.convertObject(same, Sample.class)).isSameAs(same);
        assertThat(JsonUtils.parseObject2("{\"id\":17}", Sample.class).getId()).isEqualTo(17L);
    }

    public static class Sample {

        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public Sample setId(Long id) {
            this.id = id;
            return this;
        }

        public String getName() {
            return name;
        }

        public Sample setName(String name) {
            this.name = name;
            return this;
        }
    }
}
