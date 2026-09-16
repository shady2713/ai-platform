package com.basicframework.framework.common.util.json.databind;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class JsonDatabindTest {

    private static final LocalDateTime DATE_TIME = LocalDateTime.of(2026, 8, 28, 18, 30, 15);
    private static final long TIMESTAMP =
            DATE_TIME.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

    private final ObjectMapper objectMapper = createObjectMapper();

    @Test
    void numberSerializer_keepsInclusiveSafeRangeAndQuotesUnsafeValues() throws Exception {
        NumberValues values = new NumberValues();
        values.minimumSafe = -9007199254740991L;
        values.maximumSafe = 9007199254740991L;
        values.belowMinimum = -9007199254740992L;
        values.aboveMaximum = 9007199254740992L;

        String json = objectMapper.writeValueAsString(values);

        assertThat(json)
                .contains("\"minimumSafe\":-9007199254740991")
                .contains("\"maximumSafe\":9007199254740991")
                .contains("\"belowMinimum\":\"-9007199254740992\"")
                .contains("\"aboveMaximum\":\"9007199254740992\"");
    }

    @Test
    void timestampSerializer_usesTimestampByDefaultAndRoundTrips() throws Exception {
        DefaultTimeValue value = new DefaultTimeValue();
        value.value = DATE_TIME;

        String json = objectMapper.writeValueAsString(value);
        DefaultTimeValue restored = objectMapper.readValue(json, DefaultTimeValue.class);

        assertThat(json).isEqualTo("{\"value\":" + TIMESTAMP + "}");
        assertThat(restored.value).isEqualTo(DATE_TIME);
    }

    @Test
    void timestampSerializer_honorsPatternAndRenamedProperty() throws Exception {
        FormattedTimeValue value = new FormattedTimeValue();
        value.value = DATE_TIME;

        String json = objectMapper.writeValueAsString(value);

        assertThat(json).isEqualTo("{\"createdAt\":\"2026-08-28 18:30\"}");
    }

    @Test
    void timestampSerializer_usesTimestampWhenPatternIsBlankOrInvalid() throws Exception {
        BlankPatternTimeValue blank = new BlankPatternTimeValue();
        blank.value = DATE_TIME;
        InvalidPatternTimeValue invalid = new InvalidPatternTimeValue();
        invalid.value = DATE_TIME;

        assertThat(objectMapper.writeValueAsString(blank)).isEqualTo("{\"value\":" + TIMESTAMP + "}");
        assertThat(objectMapper.writeValueAsString(invalid)).isEqualTo("{\"value\":" + TIMESTAMP + "}");
    }

    @Test
    void timestampDeserializer_usesSystemZoneForEpochMillis() throws Exception {
        long timestamp = Instant.parse("2026-08-28T10:30:15Z").toEpochMilli();

        DefaultTimeValue value = objectMapper.readValue("{\"value\":" + timestamp + "}", DefaultTimeValue.class);

        assertThat(value.value)
                .isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()));
    }

    private static ObjectMapper createObjectMapper() {
        SimpleModule module = new SimpleModule()
                .addSerializer(Long.class, NumberSerializer.INSTANCE)
                .addSerializer(Long.TYPE, NumberSerializer.INSTANCE)
                .addSerializer(LocalDateTime.class, TimestampLocalDateTimeSerializer.INSTANCE)
                .addDeserializer(LocalDateTime.class, TimestampLocalDateTimeDeserializer.INSTANCE);
        return new ObjectMapper().registerModule(module);
    }

    static class NumberValues {
        public long minimumSafe;
        public long maximumSafe;
        public long belowMinimum;
        public long aboveMaximum;
    }

    static class DefaultTimeValue {
        public LocalDateTime value;
    }

    static class FormattedTimeValue {
        @JsonProperty("createdAt")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm")
        public LocalDateTime value;
    }

    static class BlankPatternTimeValue {
        @JsonFormat
        public LocalDateTime value;
    }

    static class InvalidPatternTimeValue {
        @JsonFormat(pattern = "invalid[")
        public LocalDateTime value;
    }
}
