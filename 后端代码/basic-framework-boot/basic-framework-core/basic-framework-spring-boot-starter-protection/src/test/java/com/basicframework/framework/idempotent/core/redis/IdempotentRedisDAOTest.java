package com.basicframework.framework.idempotent.core.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class IdempotentRedisDAOTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotentRedisDAO redisDAO;

    @BeforeEach
    void setUp() {
        redisDAO = new IdempotentRedisDAO(redisTemplate);
    }

    @Test
    void setIfAbsent_prefixesKeyAndPreservesExpiryUnit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent("idempotent:order-42", "", 250, TimeUnit.MILLISECONDS))
                .thenReturn(true);

        assertThat(redisDAO.setIfAbsent("order-42", 250, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    void setIfAbsent_nullRedisResultFailsClosed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertThat(redisDAO.setIfAbsent("order-42", 1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void delete_prefixesKey() {
        redisDAO.delete("order-42");

        verify(redisTemplate).delete("idempotent:order-42");
    }

    @Test
    void invalidContractValuesAreRejectedBeforeRedisAccess() {
        assertThatThrownBy(() -> redisDAO.setIfAbsent(" ", 1, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key");
        assertThatThrownBy(() -> redisDAO.setIfAbsent("key", 0, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("大于 0");
        assertThatThrownBy(() -> redisDAO.setIfAbsent("key", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单位");
        assertThatThrownBy(() -> redisDAO.delete(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key");
    }
}
