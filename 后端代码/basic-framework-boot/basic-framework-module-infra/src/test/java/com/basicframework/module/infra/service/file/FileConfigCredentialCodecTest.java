package com.basicframework.module.infra.service.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.basicframework.framework.security.core.crypto.CredentialCipher;
import com.basicframework.module.infra.dal.dataobject.file.FileConfigDO;
import com.basicframework.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import com.basicframework.module.infra.framework.file.core.enums.FileStorageEnum;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class FileConfigCredentialCodecTest {

    private final Validator validator = mock(Validator.class);
    private final CredentialCipher credentialCipher = mock(CredentialCipher.class);
    private final FileConfigCredentialCodec codec = new FileConfigCredentialCodec(validator, credentialCipher);

    @Test
    void decrypt_withoutCiphertext_failsBeforeCipherAccess() {
        FileConfigDO config = new FileConfigDO().setStorage(FileStorageEnum.LOCAL.getStorage());

        assertThatThrownBy(() -> codec.decrypt(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("文件客户端配置密文缺失");

        verifyNoInteractions(credentialCipher);
    }

    @Test
    void decrypt_withUnknownStorage_failsClosed() {
        FileConfigDO config = new FileConfigDO().setStorage(-1).setConfigCiphertext("v1.ciphertext");
        when(credentialCipher.decrypt("v1.ciphertext", "file-client:config")).thenReturn("{}");

        assertThatThrownBy(() -> codec.decrypt(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("文件客户端存储器无效");
    }

    @Test
    void decrypt_withMalformedPlaintext_doesNotExposePayload() {
        FileConfigDO config = new FileConfigDO()
                .setStorage(FileStorageEnum.LOCAL.getStorage())
                .setConfigCiphertext("v1.ciphertext");
        when(credentialCipher.decrypt("v1.ciphertext", "file-client:config")).thenReturn("not-json");

        assertThatThrownBy(() -> codec.decrypt(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("文件客户端配置密文内容无效")
                .message()
                .doesNotContain("not-json");
    }

    @Test
    void resolve_withHydratedConfig_skipsCipherAccess() {
        LocalFileClientConfig clientConfig = new LocalFileClientConfig();
        FileConfigDO config = new FileConfigDO().setConfig(clientConfig);

        assertThat(codec.resolve(config)).isSameAs(clientConfig);
        assertThat(codec.hydrate(null)).isNull();

        verifyNoInteractions(credentialCipher);
    }
}
