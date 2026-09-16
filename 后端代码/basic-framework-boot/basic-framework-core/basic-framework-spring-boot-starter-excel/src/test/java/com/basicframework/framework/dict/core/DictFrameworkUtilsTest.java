package com.basicframework.framework.dict.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.api.dict.DictDataCommonApi;
import com.basicframework.module.system.api.dict.dto.DictDataRespDTO;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DictFrameworkUtilsTest {

    @Test
    void lookup_supportsLabelsValuesListsAndIntegerValues() {
        String dictType = "utils-status";
        DictDataCommonApi api = initialize(dictType, entry("启用", "1"), entry("停用", "0"));

        assertThat(DictFrameworkUtils.parseDictDataLabel(dictType, 1)).isEqualTo("启用");
        assertThat(DictFrameworkUtils.parseDictDataLabel(dictType, (Integer) null))
                .isNull();
        assertThat(DictFrameworkUtils.parseDictDataLabel(dictType, "missing")).isNull();
        assertThat(DictFrameworkUtils.parseDictDataValue(dictType, "停用")).isEqualTo("0");
        assertThat(DictFrameworkUtils.parseDictDataValue(dictType, "missing")).isNull();
        assertThat(DictFrameworkUtils.getDictDataLabelList(dictType)).containsExactly("启用", "停用");
        assertThat(DictFrameworkUtils.getDictDataValueList(dictType)).containsExactly("1", "0");
        verify(api).getDictDataList(dictType);
    }

    @Test
    void clearCache_forcesTheNextLookupToReload() {
        String dictType = "utils-reload";
        DictDataCommonApi api = initialize(dictType, entry("启用", "1"));
        DictFrameworkUtils.getDictDataLabelList(dictType);

        DictFrameworkUtils.clearCache();
        DictFrameworkUtils.getDictDataLabelList(dictType);

        verify(api, times(2)).getDictDataList(dictType);
    }

    @Test
    void init_rejectsMissingApiAndNonPositiveRefreshDuration() {
        DictDataCommonApi api = mock(DictDataCommonApi.class);

        assertThatNullPointerException()
                .isThrownBy(() -> DictFrameworkUtils.init(null, Duration.ofMinutes(1)))
                .withMessage("dictDataApi must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> DictFrameworkUtils.init(api, null))
                .withMessage("refreshAfterWrite must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> DictFrameworkUtils.init(api, Duration.ZERO))
                .withMessage("refreshAfterWrite must be positive");
    }

    private static DictDataCommonApi initialize(String dictType, DictDataRespDTO... entries) {
        DictDataCommonApi api = mock(DictDataCommonApi.class);
        when(api.getDictDataList(dictType)).thenReturn(List.of(entries));
        DictFrameworkUtils.init(api, Duration.ofMinutes(1));
        return api;
    }

    private static DictDataRespDTO entry(String label, String value) {
        DictDataRespDTO entry = new DictDataRespDTO();
        entry.setLabel(label);
        entry.setValue(value);
        return entry;
    }
}
