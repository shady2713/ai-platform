package com.basicframework.module.infra.framework.file.core.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link FileClient} 默认方法能力边界测试。
 *
 * <p>契约：默认实现声明了通用能力边界——仅 S3 支持的上传/读取签名与
 * 元数据读取以 {@link UnsupportedOperationException} 失败关闭；私有预签名上传默认
 * 不支持，私有读取默认支持；无资源客户端 {@link #close()} 是空操作。
 */
class FileClientTest {

    private final FileClient client = new FileClient() {

        @Override
        public Long getId() {
            return 1L;
        }

        @Override
        public String upload(byte[] content, String path, String type) {
            return null;
        }

        @Override
        public void delete(String path) {}

        @Override
        public byte[] getContent(String path) {
            return new byte[0];
        }
    };

    @Test
    void unsupportedS3Capabilities_failWithUnsupportedOperation() {
        assertThatThrownBy(() -> client.presignPutUrl("a.txt", 1L, "text/plain", Duration.ofMinutes(1)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("不支持的操作");
        assertThatThrownBy(() -> client.getMetadata("a.txt"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("不支持的操作");
        assertThatThrownBy(() -> client.presignGetUrl("https://cdn.example.com/a.txt", 60))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("不支持的操作");
    }

    @Test
    void defaultCapabilityFlags_privateReadOnPresignedUploadOff() {
        assertThat(client.supportsPrivatePresignedUpload()).isFalse();
        assertThat(client.supportsPrivateRead()).isTrue();
    }

    @Test
    void close_withoutManagedResources_isNoop() {
        client.close();
    }
}
