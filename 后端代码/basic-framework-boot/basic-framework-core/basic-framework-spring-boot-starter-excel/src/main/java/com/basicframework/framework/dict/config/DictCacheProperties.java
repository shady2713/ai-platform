package com.basicframework.framework.dict.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 字典缓存配置。 */
@ConfigurationProperties("basic-framework.dict")
@Validated
@Data
public class DictCacheProperties {

    /** 字典缓存异步刷新周期。 */
    @NotNull
    private Duration cacheRefreshAfterWrite = Duration.ofMinutes(1);

    @AssertTrue(message = "basic-framework.dict.cache-refresh-after-write 必须大于 0")
    public boolean isCacheRefreshAfterWriteValid() {
        return cacheRefreshAfterWrite != null
                && !cacheRefreshAfterWrite.isZero()
                && !cacheRefreshAfterWrite.isNegative();
    }
}
