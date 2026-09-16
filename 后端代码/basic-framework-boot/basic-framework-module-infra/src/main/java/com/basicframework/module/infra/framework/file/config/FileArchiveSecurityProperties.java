package com.basicframework.module.infra.framework.file.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** ZIP 上传安全限制。 */
@ConfigurationProperties(prefix = "basic-framework.file.archive")
@Validated
@Data
public class FileArchiveSecurityProperties {

    /** 单个压缩包允许的最大条目数。 */
    @Min(1)
    private int maxEntries = 1000;

    /** 单个压缩包允许的最大总展开量。 */
    @NotNull
    private DataSize maxExpandedSize = DataSize.ofMegabytes(128);

    /** 总展开量与上传字节数之间允许的最大比例。 */
    @DecimalMin("1.0")
    private double maxCompressionRatio = 100D;

    @AssertTrue(message = "basic-framework.file.archive.max-expanded-size 必须大于 0")
    public boolean isMaxExpandedSizeValid() {
        return maxExpandedSize != null && maxExpandedSize.toBytes() > 0;
    }
}
