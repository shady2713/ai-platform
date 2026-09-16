package com.basicframework.module.infra.framework.file.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "basic-framework.file.presigned-upload")
@Validated
@Data
public class FilePresignedUploadProperties {

    @NotNull
    private Duration ttl = Duration.ofMinutes(15);

    @NotNull
    private DataSize maxSize = DataSize.ofMegabytes(16);

    @AssertTrue(message = "预签名上传最大文件大小必须在 1 字节至 64 MB 之间")
    public boolean isMaxSizeValid() {
        return maxSize != null
                && maxSize.toBytes() > 0
                && maxSize.toBytes() <= DataSize.ofMegabytes(64).toBytes();
    }

    @AssertTrue(message = "预签名上传有效期必须在 1 分钟至 24 小时之间")
    public boolean isTtlValid() {
        return ttl != null && ttl.compareTo(Duration.ofMinutes(1)) >= 0 && ttl.compareTo(Duration.ofHours(24)) <= 0;
    }
}
