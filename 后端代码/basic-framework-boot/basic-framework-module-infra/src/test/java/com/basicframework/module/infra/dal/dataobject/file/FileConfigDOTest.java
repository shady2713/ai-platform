package com.basicframework.module.infra.dal.dataobject.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import org.junit.jupiter.api.Test;

class FileConfigDOTest {

    @Test
    void toString_omitsCiphertextAndDecryptedConfig() {
        FileConfigDO config = new FileConfigDO()
                .setId(1L)
                .setConfigCiphertext("v1.sensitive.ciphertext")
                .setConfig(new LocalFileClientConfig());

        assertThat(config.toString()).doesNotContain("sensitive", "LocalFileClientConfig");
    }
}
