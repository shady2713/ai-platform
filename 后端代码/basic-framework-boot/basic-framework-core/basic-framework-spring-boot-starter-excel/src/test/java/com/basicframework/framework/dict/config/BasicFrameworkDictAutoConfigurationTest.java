package com.basicframework.framework.dict.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.module.system.api.dict.DictDataCommonApi;
import org.junit.jupiter.api.Test;

class BasicFrameworkDictAutoConfigurationTest {

    @Test
    void dictUtils_initializesSharedDictionaryBoundary() {
        DictCacheProperties properties = new DictCacheProperties();

        DictFrameworkUtils result =
                new BasicFrameworkDictAutoConfiguration().dictUtils(mock(DictDataCommonApi.class), properties);

        assertThat(result).isNotNull();
    }
}
