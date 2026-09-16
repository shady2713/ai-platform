package com.basicframework.module.infra.service.file;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.framework.common.util.validation.ValidationUtils;
import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.client.FileClientConfig;
import com.basicframework.module.infra.framework.file.core.client.s3.S3FileClientConfig;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import jakarta.validation.Validator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 负责文件客户端配置的校验、凭据保留和加密存储。 */
@Component
@RequiredArgsConstructor
public class FileConfigCredentialCodec {

    private static final String CONFIG_CONTEXT = "file-client:config";

    private final Validator validator;
    private final CredentialCipher credentialCipher;

    FileClientConfig parse(Integer storage, Map<String, Object> config, FileClientConfig existingConfig) {
        FileStorageEnum storageEnum = FileStorageEnum.getByStorage(storage);
        Assert.notNull(storageEnum, "不支持的文件存储器");
        FileClientConfig clientConfig =
                JsonUtils.parseObject2(JsonUtils.toJsonString(config), storageEnum.getConfigClass());
        preserveS3Secret(clientConfig, existingConfig);
        ValidationUtils.validate(validator, clientConfig);
        return clientConfig;
    }

    String encrypt(FileClientConfig config) {
        return credentialCipher.encrypt(JsonUtils.toJsonString(config), CONFIG_CONTEXT);
    }

    FileClientConfig decrypt(FileConfigDO config) {
        if (!StringUtils.hasText(config.getConfigCiphertext())) {
            throw new IllegalStateException("文件客户端配置密文缺失");
        }
        String json = credentialCipher.decrypt(config.getConfigCiphertext(), CONFIG_CONTEXT);
        FileStorageEnum storageEnum = FileStorageEnum.getByStorage(config.getStorage());
        if (storageEnum == null) {
            throw new IllegalStateException("文件客户端存储器无效");
        }
        try {
            return JsonUtils.parseObject2(json, storageEnum.getConfigClass());
        } catch (RuntimeException ignored) {
            throw new IllegalStateException("文件客户端配置密文内容无效");
        }
    }

    FileClientConfig resolve(FileConfigDO config) {
        return config.getConfig() != null ? config.getConfig() : decrypt(config);
    }

    FileConfigDO hydrate(FileConfigDO config) {
        if (config != null) {
            config.setConfig(decrypt(config));
        }
        return config;
    }

    private static void preserveS3Secret(FileClientConfig clientConfig, FileClientConfig existingConfig) {
        if (!(clientConfig instanceof S3FileClientConfig s3Config)
                || StringUtils.hasText(s3Config.getAccessSecret())
                || !(existingConfig instanceof S3FileClientConfig existingS3Config)) {
            return;
        }
        s3Config.setAccessSecret(existingS3Config.getAccessSecret());
    }
}
