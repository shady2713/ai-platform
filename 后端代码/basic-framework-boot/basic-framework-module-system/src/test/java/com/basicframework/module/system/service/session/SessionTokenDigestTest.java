package com.basicframework.module.system.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class SessionTokenDigestTest {

    @Test
    void digest_usesSha256WithoutNormalizingOpaqueToken() {
        assertThat(SessionTokenDigest.digest("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SessionTokenDigest.digest(" abc ")).isNotEqualTo(SessionTokenDigest.digest("abc"));
        assertThat(SessionTokenDigest.digest("ABC")).isNotEqualTo(SessionTokenDigest.digest("abc"));
    }

    @Test
    void digest_nullToken_failsClosed() {
        assertThatNullPointerException().isThrownBy(() -> SessionTokenDigest.digest(null));
    }

    @Test
    void refreshFamilyPreservesLegacyTokenAndExtractsStableNewPrefix() {
        String legacy = "a".repeat(64);
        assertThat(SessionTokenDigest.refreshFamily(legacy)).isEqualTo(legacy);
        assertThat(SessionTokenDigest.refreshFamily(legacy + "b".repeat(64))).isEqualTo(legacy);
    }

    @Test
    void refreshFamilyRejectsMissingMalformedOrTruncatedCredentials() {
        assertThat(SessionTokenDigest.refreshFamily(null)).isNull();
        assertThat(SessionTokenDigest.refreshFamily("")).isNull();
        assertThat(SessionTokenDigest.refreshFamily("z".repeat(64))).isNull();
        for (int length : new int[] {63, 65, 127, 129}) {
            assertThat(SessionTokenDigest.refreshFamily("a".repeat(length))).isNull();
        }
    }
}
