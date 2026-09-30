package com.basicframework.module.ai.service.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 会话票据（X05 单测）：明文随机且只存摘要、摘要比对是常量时间且不接受空值/长度不符。
 */
class AiRealtimeSessionTicketsTest {

    private final AiRealtimeSessionTickets tickets = new AiRealtimeSessionTickets();

    @Test
    void ticketAndSessionKeyAreRandomUrlSafeAndDistinguishable() {
        Set<String> issued = new HashSet<>();
        for (int index = 0; index < 32; index++) {
            String ticket = tickets.newTicket();
            assertThat(ticket)
                    .hasSize(43)
                    .doesNotContain("=")
                    .doesNotContain("+")
                    .doesNotContain("/");
            assertThat(issued.add(ticket)).isTrue();
        }
        assertThat(tickets.newSessionKey()).startsWith("rts_");
        assertThat(tickets.newSessionKey()).isNotEqualTo(tickets.newSessionKey());
    }

    @Test
    void digestIsSha256HexAndBlankInputIsEmpty() {
        String digest = tickets.digest("ticket-plain").orElseThrow();

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(tickets.digest(" ticket-plain ")).contains(digest);
        assertThat(tickets.digest(null)).isEmpty();
        assertThat(tickets.digest("   ")).isEmpty();
    }

    @Test
    void digestComparisonIsExactAndNeverThrowsOnHostileInput() {
        String digest = tickets.digest("ticket-plain").orElseThrow();

        assertThat(tickets.digestMatches(digest, "ticket-plain")).isTrue();
        assertThat(tickets.digestMatches(digest, "ticket-plain ")).isTrue();
        assertThat(tickets.digestMatches(digest, "ticket-plai")).isFalse();
        assertThat(tickets.digestMatches(digest, "other-ticket")).isFalse();
        assertThat(tickets.digestMatches(digest, null)).isFalse();
        assertThat(tickets.digestMatches(null, "ticket-plain")).isFalse();
        assertThat(tickets.digestMatches("short", "ticket-plain")).isFalse();
    }
}
