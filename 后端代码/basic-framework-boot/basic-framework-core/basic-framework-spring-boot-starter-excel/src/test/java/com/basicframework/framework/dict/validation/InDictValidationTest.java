package com.basicframework.framework.dict.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.module.system.api.dict.DictDataCommonApi;
import com.basicframework.module.system.api.dict.dto.DictDataRespDTO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InDictValidationTest {

    private static final String DICT_TYPE = "validation-status";
    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        DictDataRespDTO enabled = new DictDataRespDTO();
        enabled.setLabel("启用");
        enabled.setValue("enabled$1");
        DictDataCommonApi api = mock(DictDataCommonApi.class);
        when(api.getDictDataList(DICT_TYPE)).thenReturn(List.of(enabled));
        DictFrameworkUtils.init(api, Duration.ofMinutes(1));
    }

    @Test
    void scalarValidator_acceptsNullAndCaseInsensitiveMatches() {
        assertThat(VALIDATOR.validate(new ScalarFixture(null))).isEmpty();
        assertThat(VALIDATOR.validate(new ScalarFixture("ENABLED$1"))).isEmpty();
    }

    @Test
    void scalarValidator_rejectsUnknownValuesAndBuildsLiteralMessage() {
        assertThat(VALIDATOR.validate(new ScalarFixture("missing")))
                .singleElement()
                .extracting(violation -> violation.getMessage())
                .isEqualTo("必须在指定范围 [enabled$1]");
    }

    @Test
    void collectionValidator_handlesEmptyValidInvalidAndNullElements() {
        assertThat(VALIDATOR.validate(new CollectionFixture(List.of()))).isEmpty();
        assertThat(VALIDATOR.validate(new CollectionFixture(List.of("ENABLED$1"))))
                .isEmpty();
        assertThat(VALIDATOR.validate(new CollectionFixture(List.of("missing"))))
                .hasSize(1);
        assertThat(VALIDATOR.validate(new CollectionFixture(java.util.Collections.singletonList(null))))
                .hasSize(1);
    }

    private record ScalarFixture(@InDict(type = DICT_TYPE) String value) {}

    private record CollectionFixture(@InDict(type = DICT_TYPE) Collection<String> values) {}
}
