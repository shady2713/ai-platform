package com.basicframework.framework.excel.core.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.idev.excel.metadata.data.ReadCellData;
import cn.idev.excel.metadata.property.ExcelContentProperty;
import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.framework.excel.core.annotations.DictFormat;
import com.basicframework.module.system.api.dict.DictDataCommonApi;
import com.basicframework.module.system.api.dict.dto.DictDataRespDTO;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExcelConverterTest {

    private static final String DICT_TYPE = "converter-status";

    @BeforeEach
    void setUp() {
        DictDataCommonApi api = mock(DictDataCommonApi.class);
        DictDataRespDTO enabled = new DictDataRespDTO();
        enabled.setLabel("启用");
        enabled.setValue("1");
        when(api.getDictDataList(DICT_TYPE)).thenReturn(List.of(enabled));
        DictFrameworkUtils.init(api, Duration.ofMinutes(1));
    }

    @Test
    void moneyConvert_formatsCentsWithTwoDecimalPlaces() {
        MoneyConvert converter = new MoneyConvert();

        assertThat(converter.convertToExcelData(123, null, null).getStringValue())
                .isEqualTo("1.23");
        assertThat(converter.convertToExcelData(-1, null, null).getStringValue())
                .isEqualTo("-0.01");
    }

    @Test
    void jsonConvert_serializesStructuredValues() {
        JsonConvert converter = new JsonConvert();

        assertThat(converter
                        .convertToExcelData(Map.of("enabled", true), null, null)
                        .getStringValue())
                .isEqualTo("{\"enabled\":true}");
    }

    @Test
    void dictConvert_mapsLabelsValuesNullAndUnknownEntries() throws NoSuchFieldException {
        DictConvert converter = new DictConvert();
        ExcelContentProperty contentProperty = contentProperty();

        assertThat(converter.convertToJavaData(new ReadCellData<>("启用"), contentProperty, null))
                .isEqualTo(1);
        assertThat(converter.convertToJavaData(new ReadCellData<>("未知"), contentProperty, null))
                .isNull();
        assertThat(converter.convertToExcelData(1, contentProperty, null).getStringValue())
                .isEqualTo("启用");
        assertThat(converter.convertToExcelData(2, contentProperty, null).getStringValue())
                .isEmpty();
        assertThat(converter.convertToExcelData(null, contentProperty, null).getStringValue())
                .isEmpty();
    }

    @Test
    void convertersRejectImplicitGlobalRegistration() {
        for (Object converter : List.of(new MoneyConvert(), new JsonConvert(), new DictConvert())) {
            assertThatThrownBy(() -> ((cn.idev.excel.converters.Converter<?>) converter).supportJavaTypeKey())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> ((cn.idev.excel.converters.Converter<?>) converter).supportExcelTypeKey())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static ExcelContentProperty contentProperty() throws NoSuchFieldException {
        Field field = Fixture.class.getDeclaredField("status");
        ExcelContentProperty contentProperty = mock(ExcelContentProperty.class);
        when(contentProperty.getField()).thenReturn(field);
        return contentProperty;
    }

    private static final class Fixture {
        @DictFormat(DICT_TYPE)
        private Integer status;
    }
}
