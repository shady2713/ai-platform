package com.basicframework.module.ai.domain.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** D07 结果 Schema：码唯一且非空，码集合即投影白名单。 */
class AiResultSchemaTest {

    @Test
    void exposesColumnCodesAsProjectionWhitelist() {
        AiResultSchema schema = new AiResultSchema(List.of(
                new AiResultSchema.Column("customer_name", "customer_name", "STRING", "NONE"),
                new AiResultSchema.Column("net_amount", "net_amount", "DECIMAL", "CURRENCY")));

        assertThat(schema.codes()).containsExactly("customer_name", "net_amount");
        assertThat(schema.columns()).hasSize(2);
        assertThat(new AiResultSchema(null).codes()).isEmpty();
    }

    @Test
    void rejectsBlankOrDuplicateColumnCodes() {
        assertThatThrownBy(() -> new AiResultSchema(List.of(
                        new AiResultSchema.Column("net_amount", "net_amount", "DECIMAL", "CURRENCY"),
                        new AiResultSchema.Column("net_amount", "net_amount", "DECIMAL", "CURRENCY"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiResultSchema(List.of(new AiResultSchema.Column("  ", "x", "STRING", "NONE"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiResultSchema(List.of(new AiResultSchema.Column(null, "x", "STRING", "NONE"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
