package com.basicframework.framework.security.core.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.security.config.SecurityProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    @Test
    void encrypt_usesRandomIvAndBindsCiphertextToContext() {
        CredentialCipher cipher = cipher();

        String first = cipher.encrypt("TOP-SECRET", "sms-channel");
        String second = cipher.encrypt("TOP-SECRET", "sms-channel");

        assertThat(first).isNotEqualTo(second).doesNotContain("TOP-SECRET");
        assertThat(cipher.isEncryptedValue(first)).isTrue();
        assertThat(cipher.isEncryptedValue(null)).isFalse();
        assertThat(cipher.isEncryptedValue("legacy-plaintext")).isFalse();
        assertThat(cipher.isEncryptedValue("v1..ciphertext")).isFalse();
        assertThat(cipher.decrypt(first, "sms-channel")).isEqualTo("TOP-SECRET");
        assertThatThrownBy(() -> cipher.decrypt(first, "file-config"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("凭据解密失败");
    }

    @Test
    void decrypt_rejectsUnknownVersionWithoutEchoingCiphertext() {
        CredentialCipher cipher = cipher();

        assertThatThrownBy(() -> cipher.decrypt("v2.secret.value", "sms-channel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的凭据密文版本")
                .hasMessageNotContaining("secret");
    }

    @Test
    void operation_withoutKey_failsClosed() {
        CredentialCipher cipher = new CredentialCipher(new SecurityProperties());

        assertThatThrownBy(() -> cipher.encrypt("secret", "sms-channel"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("凭据主密钥未配置");
    }

    @Test
    void constructor_rejectsMalformedOrWrongLengthMasterKey() {
        SecurityProperties malformed = new SecurityProperties();
        malformed.setCredentialEncryptionKey("not-base64");
        SecurityProperties shortKey = new SecurityProperties();
        shortKey.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[31]));

        assertThatThrownBy(() -> new CredentialCipher(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("凭据主密钥必须是合法 Base64 值");
        assertThatThrownBy(() -> new CredentialCipher(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("凭据主密钥必须是 32 字节 Base64 值");
    }

    @Test
    void operations_rejectBlankValuesAndContextsBeforeCryptography() {
        CredentialCipher cipher = cipher();

        assertThatThrownBy(() -> cipher.encrypt(" ", "context"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待加密凭据");
        assertThatThrownBy(() -> cipher.encrypt("secret", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据上下文");
        assertThatThrownBy(() -> cipher.decrypt(" ", "context"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待解密凭据");
    }

    @Test
    void decrypt_rejectsMalformedUrlBase64AsGenericDecryptionFailure() {
        assertThatThrownBy(() -> cipher().decrypt("v1.!.!", "context"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("凭据解密失败");
    }

    private static CredentialCipher cipher() {
        SecurityProperties properties = new SecurityProperties();
        properties.setCredentialEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        return new CredentialCipher(properties);
    }
}
