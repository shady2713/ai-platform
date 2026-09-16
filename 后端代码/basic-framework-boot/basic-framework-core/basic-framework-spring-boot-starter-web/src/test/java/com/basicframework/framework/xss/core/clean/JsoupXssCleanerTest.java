package com.basicframework.framework.xss.core.clean;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsoupXssCleanerTest {

    private final JsoupXssCleaner cleaner = new JsoupXssCleaner();

    @Test
    void clean_removesExecutableContentButPreservesReviewedMarkup() {
        String value =
                cleaner.clean("<script>alert(1)</script><p class='note' style='background:url(javascript:1)'>safe "
                        + "<a href='https://example.com' target='_blank'>link</a></p>");

        assertThat(value)
                .doesNotContain("script", "alert", "style", "javascript")
                .contains("<p class=\"note\">", "href=\"https://example.com\"", "target=\"_blank\"");
    }

    @Test
    void clean_rejectsDataImageProtocolFromGlobalPolicy() {
        assertThat(cleaner.clean("<img src='data:image/svg+xml,<svg onload=alert(1)>'>"))
                .doesNotContain("data:", "onload", "alert");
    }
}
