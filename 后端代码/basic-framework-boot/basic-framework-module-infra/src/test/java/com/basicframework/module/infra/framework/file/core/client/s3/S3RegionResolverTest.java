package com.basicframework.module.infra.framework.file.core.client.s3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class S3RegionResolverTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "s3.amazonaws.com",
                "https://s3.amazonaws.com",
                "s3-accelerate.amazonaws.com",
                "s3.accelerate.amazonaws.com",
                "storage.aliyuncs.com",
                "oss-.aliyuncs.com",
                "storage.myqcloud.com",
                "cos..myqcloud.com",
                "http://[invalid"
            })
    void endpointsWithoutInferableRegionUseTheDefault(String endpoint) {
        S3FileClientConfig config = new S3FileClientConfig().setEndpoint(endpoint);

        assertThat(S3RegionResolver.resolveRegion(config)).isEqualTo("us-east-1");
    }

    @Test
    void explicitRegionOverridesTheEndpointRegion() {
        S3FileClientConfig config = new S3FileClientConfig()
                .setEndpoint("s3.us-west-2.amazonaws.com")
                .setRegion("us-east-2");

        assertThat(S3RegionResolver.resolveRegion(config)).isEqualTo("us-east-2");
    }
}
