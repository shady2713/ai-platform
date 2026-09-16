package com.basicframework.framework.ip.core.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.idev.excel.metadata.GlobalConfiguration;
import cn.idev.excel.metadata.data.ReadCellData;
import cn.idev.excel.metadata.property.ExcelContentProperty;
import org.junit.jupiter.api.Test;

class AreaConvertTest {

    private final AreaConvert areaConvert = new AreaConvert();

    @Test
    void convertToJavaData_convertsAdministrativePathToTargetFieldType() throws NoSuchFieldException {
        ReadCellData<?> readCellData = mock(ReadCellData.class);
        ExcelContentProperty contentProperty = mock(ExcelContentProperty.class);
        when(readCellData.getStringValue()).thenReturn("浙江省/杭州市/西湖区");
        when(contentProperty.getField()).thenReturn(Fixture.class.getDeclaredField("areaId"));

        Object result = areaConvert.convertToJavaData(readCellData, contentProperty, mock(GlobalConfiguration.class));

        assertThat(result).isEqualTo(330106L);
    }

    @Test
    void convertToJavaData_rejectsUnknownAdministrativePath() {
        ReadCellData<?> readCellData = mock(ReadCellData.class);
        ExcelContentProperty contentProperty = mock(ExcelContentProperty.class);
        when(readCellData.getStringValue()).thenReturn("浙江省/不存在/西湖区");

        assertThat(areaConvert.convertToJavaData(readCellData, contentProperty, mock(GlobalConfiguration.class)))
                .isNull();
        verifyNoInteractions(contentProperty);
    }

    @Test
    void unsupportedTypeMetadata_failsLoudly() {
        assertThatThrownBy(areaConvert::supportJavaTypeKey).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(areaConvert::supportExcelTypeKey).isInstanceOf(UnsupportedOperationException.class);
    }

    private static final class Fixture {

        @SuppressWarnings("unused")
        private Long areaId;
    }
}
