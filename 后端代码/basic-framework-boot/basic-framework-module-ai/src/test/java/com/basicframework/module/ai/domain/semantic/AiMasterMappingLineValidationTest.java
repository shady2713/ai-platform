package com.basicframework.module.ai.domain.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.time.LocalDateTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Y02 登记事实的**唯一校验入口**：源键格式、有效期窗口、匹配方式词表与对象类型词表。
 *
 * <p>负向用例是主体：任何"不确定"的输入都必须被拒绝，因为这条记录决定了"哪些标识算同一实体"。
 */
class AiMasterMappingLineValidationTest {

    private static final LocalDateTime JAN = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Test
    void acceptsExplicitSourceKeyAndNormalizesMatchMethod() {
        AiMasterMappingLine line =
                AiMasterMappingLine.of(9L, 7L, "customer", "C-2026/001", "  杭州云启  ", "manual", JAN, null);

        assertThat(line.sourceKey()).isEqualTo("C-2026/001");
        assertThat(line.sourceName()).isEqualTo("杭州云启");
        assertThat(line.matchMethod()).isEqualTo("MANUAL");
        assertThat(line.masterObjectId()).isEqualTo(9L);
        assertThat(line.applicationId()).isEqualTo(7L);
    }

    @Test
    void rejectsBlankOrMalformedIdentity() {
        assertInvalid(() -> AiMasterMappingLine.of(null, 7L, "customer", "C-1", "", "MANUAL", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(0L, 7L, "customer", "C-1", "", "MANUAL", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, null, "customer", "C-1", "", "MANUAL", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, null, "C-1", "", "MANUAL", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "Customer", "C-1", "", "MANUAL", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", null, "", "MANUAL", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", " C-1", "", "MANUAL", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", "C 1", "", "MANUAL", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", "C-1", "名".repeat(129), "MANUAL", JAN, null));
    }

    @Test
    void rejectsMissingOrInvertedValidityWindow() {
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", "C-1", "", "MANUAL", null, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", "C-1", "", "MANUAL", JAN, JAN));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", "C-1", "", "MANUAL", JAN, JAN.minusDays(1)));
    }

    @Test
    void rejectsUnknownMatchMethodInsteadOfDefaulting() {
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", "C-1", "", null, JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", "C-1", "", " ", JAN, null));
        assertInvalid(() -> AiMasterMappingLine.of(9L, 7L, "customer", "C-1", "", "SIMILARITY", JAN, null));
        // 空白只做去除，不做"猜一个默认值"：带空白的合法取值照常接受
        assertThat(AiMasterMappingLine.of(9L, 7L, "customer", "C-1", "", " TRUSTED_FEED ", JAN, null)
                        .matchMethod())
                .isEqualTo("TRUSTED_FEED");
    }

    @Test
    void matchMethodVocabularyIsClosed() {
        assertThat(AiMasterMappingMatchMethod.parse("trusted_feed")).contains(AiMasterMappingMatchMethod.TRUSTED_FEED);
        assertThat(AiMasterMappingMatchMethod.parse("MANUAL")).contains(AiMasterMappingMatchMethod.MANUAL);
        assertThat(AiMasterMappingMatchMethod.parse("MANUAL ")).contains(AiMasterMappingMatchMethod.MANUAL);
        assertThat(AiMasterMappingMatchMethod.parse(null)).isEmpty();
        assertThat(AiMasterMappingMatchMethod.parse("")).isEmpty();
        assertThat(AiMasterMappingMatchMethod.parse("auto")).isEmpty();
        assertThat(AiMasterMappingMatchMethod.values()).hasSize(2);
        assertThat(AiMasterMappingMatchMethod.valueOf("MANUAL").name().toLowerCase(Locale.ROOT))
                .isEqualTo("manual");
    }

    @Test
    void objectTypeVocabularyIsClosed() {
        assertThat(AiMasterObjectType.parse("customer")).contains(AiMasterObjectType.CUSTOMER);
        assertThat(AiMasterObjectType.parse(" ORGANIZATION ")).contains(AiMasterObjectType.ORGANIZATION);
        assertThat(AiMasterObjectType.parse("other")).contains(AiMasterObjectType.OTHER);
        assertThat(AiMasterObjectType.parse(null)).isEmpty();
        assertThat(AiMasterObjectType.parse(" ")).isEmpty();
        assertThat(AiMasterObjectType.parse("CUSTOM")).isEmpty();
        assertThat(AiMasterObjectType.values()).hasSize(6);
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID.getCode());
    }
}
