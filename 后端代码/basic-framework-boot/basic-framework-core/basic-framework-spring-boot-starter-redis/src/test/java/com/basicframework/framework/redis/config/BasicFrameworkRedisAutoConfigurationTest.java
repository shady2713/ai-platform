package com.basicframework.framework.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

class BasicFrameworkRedisAutoConfigurationTest {

    @Test
    void redisTemplate_usesUnifiedConnectionAndSerializers() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        RedisTemplate<String, Object> template =
                new BasicFrameworkRedisAutoConfiguration().redisTemplate(connectionFactory);

        assertThat(template.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(template.getKeySerializer()).isSameAs(RedisSerializer.string());
        assertThat(template.getHashKeySerializer()).isSameAs(RedisSerializer.string());
        assertThat(template.getValueSerializer()).isNotNull();
        assertThat(template.getHashValueSerializer()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildRedisSerializer_supportsJavaTimeValues() {
        RedisSerializer<Object> serializer =
                (RedisSerializer<Object>) BasicFrameworkRedisAutoConfiguration.buildRedisSerializer();

        byte[] serialized = serializer.serialize(Map.of("createdAt", LocalDateTime.of(2026, 9, 3, 3, 0)));

        assertThat(serialized).isNotEmpty();
    }
}
