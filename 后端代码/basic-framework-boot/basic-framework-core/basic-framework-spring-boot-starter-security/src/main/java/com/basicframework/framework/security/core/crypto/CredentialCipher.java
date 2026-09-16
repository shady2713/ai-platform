package com.basicframework.framework.security.core.crypto;

import com.basicframework.framework.security.config.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** 可恢复凭据的版本化 AES-GCM 加密接缝。 */
public final class CredentialCipher {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String VERSION = "v1";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] key;

    public CredentialCipher(SecurityProperties properties) {
        String encodedKey = properties.getCredentialEncryptionKey();
        this.key = StringUtils.hasText(encodedKey) ? decodeStandardBase64(encodedKey) : null;
        if (key != null && key.length != 32) {
            throw new IllegalArgumentException("凭据主密钥必须是 32 字节 Base64 值");
        }
    }

    /** 使用随机 IV 加密，并将密文绑定到调用方提供的业务上下文。 */
    public String encrypt(String plaintext, String context) {
        requireConfigured();
        Assert.hasText(plaintext, "待加密凭据不能为空");
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec("AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(context));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + "." + encode(iv) + "." + encode(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("凭据加密失败", exception);
        }
    }

    /** 解密指定业务上下文中的凭据；上下文不匹配或密文损坏时关闭失败。 */
    public String decrypt(String value, String context) {
        requireConfigured();
        Assert.hasText(value, "待解密凭据不能为空");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("不支持的凭据密文版本");
        }
        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    keySpec("AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.getUrlDecoder().decode(parts[1])));
            cipher.updateAAD(aad(context));
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("凭据解密失败", exception);
        }
    }

    /** 判断值是否采用当前支持的版本化密文格式，不执行解密。 */
    public boolean isEncryptedValue(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String[] parts = value.split("\\.", -1);
        return parts.length == 3 && VERSION.equals(parts[0]) && !parts[1].isEmpty() && !parts[2].isEmpty();
    }

    /** 确认主密钥已配置；依赖凭据能力的组件应在初始化阶段调用。 */
    public void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("凭据主密钥未配置");
        }
    }

    private SecretKeySpec keySpec(String algorithm) {
        return new SecretKeySpec(key, algorithm);
    }

    private static byte[] aad(String context) {
        Assert.hasText(context, "凭据上下文不能为空");
        return ("basic-framework:" + context).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodeStandardBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("凭据主密钥必须是合法 Base64 值", exception);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
