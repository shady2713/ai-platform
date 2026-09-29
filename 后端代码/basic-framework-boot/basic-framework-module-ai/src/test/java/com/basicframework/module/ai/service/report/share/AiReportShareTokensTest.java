package com.basicframework.module.ai.service.report.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 分享令牌口径（X11）：明文 32 字节 SecureRandom → Base64URL（无填充），摘要 SHA-256 小写十六进制。
 * 生成与校验共用同一口径，杜绝两套编码漂移。
 */
class AiReportShareTokensTest {

    @Test
    void generatedTokensAreUrlSafeAndNeverRepeat() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            String token = AiReportShareTokens.generate();
            // 32 字节 → Base64URL 无填充固定 43 字符，只含 URL 安全字母表
            assertThat(token).hasSize(43).matches("^[A-Za-z0-9_-]+$");
            tokens.add(token);
        }
        assertThat(tokens).hasSize(64);
    }

    @Test
    void digestIsTheLowercaseSha256HexOfThePlaintext() {
        // 与独立实现互证的已知向量：SHA-256("abc")
        assertThat(AiReportShareTokens.digest("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String token = AiReportShareTokens.generate();
        assertThat(AiReportShareTokens.digest(token)).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void digestStaysTheSameForTheSamePlaintextAndDiffersAcrossTokens() {
        String token = AiReportShareTokens.generate();
        assertThat(AiReportShareTokens.digest(token)).isEqualTo(AiReportShareTokens.digest(token));
        assertThat(AiReportShareTokens.digest(token)).isNotEqualTo(AiReportShareTokens.digest(token + " "));
    }
}
