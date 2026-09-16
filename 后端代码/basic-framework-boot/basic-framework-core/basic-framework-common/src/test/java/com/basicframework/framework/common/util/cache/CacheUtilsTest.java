package com.basicframework.framework.common.util.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CacheUtilsTest {

    @Test
    void buildAsyncReloadingCache_loadsInitialValueAndCachesIt() {
        AtomicInteger loads = new AtomicInteger();
        LoadingCache<String, Integer> cache = CacheUtils.buildAsyncReloadingCache(
                Duration.ofMinutes(5), CacheLoader.from(key -> loads.incrementAndGet()));

        assertThat(cache.getUnchecked("key")).isEqualTo(1);
        assertThat(cache.getUnchecked("key")).isEqualTo(1);
        assertThat(loads).hasValue(1);
    }
}
