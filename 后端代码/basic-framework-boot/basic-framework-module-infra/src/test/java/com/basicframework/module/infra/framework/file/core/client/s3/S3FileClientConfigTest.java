package com.basicframework.module.infra.framework.file.core.client.s3;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class S3FileClientConfigTest {

    @Test
    void qiniuRequiresCustomDomain_butOtherProvidersCanDeriveIt() {
        S3FileClientConfig qiniu = new S3FileClientConfig().setEndpoint("s3-cn-east-1.qiniucs.com");
        S3FileClientConfig aliyun = new S3FileClientConfig().setEndpoint("oss-cn-beijing.aliyuncs.com");

        assertThat(qiniu.isDomainValid()).isFalse();
        assertThat(qiniu.setDomain("https://cdn.example.com").isDomainValid()).isTrue();
        assertThat(aliyun.isDomainValid()).isTrue();
    }

    @Test
    void toString_neverContainsAccessCredentials() {
        S3FileClientConfig config = new S3FileClientConfig()
                .setEndpoint("https://s3.example.com")
                .setAccessKey("public-key-id")
                .setAccessSecret("private-secret-value");

        assertThat(config.toString()).doesNotContain("public-key-id", "private-secret-value");
    }
}
