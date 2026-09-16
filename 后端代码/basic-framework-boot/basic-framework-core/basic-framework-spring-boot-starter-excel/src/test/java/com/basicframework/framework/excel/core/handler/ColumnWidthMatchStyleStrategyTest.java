package com.basicframework.framework.excel.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.idev.excel.enums.CellDataTypeEnum;
import cn.idev.excel.metadata.data.RichTextStringData;
import cn.idev.excel.metadata.data.WriteCellData;
import cn.idev.excel.write.metadata.holder.WriteSheetHolder;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ColumnWidthMatchStyleStrategyTest {

    @Test
    void setColumnWidth_handlesHeadersSupportedTypesCapAndNoShrink() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("data");
            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellValue("标题");
            WriteSheetHolder holder = holder(sheet);
            ExposedStrategy strategy = new ExposedStrategy();

            strategy.apply(holder, List.of(), cell, true);
            int headerWidth = sheet.getColumnWidth(0);
            strategy.apply(holder, List.of(cellData(CellDataTypeEnum.STRING, "a", null, null, null)), cell, false);
            assertThat(sheet.getColumnWidth(0)).isEqualTo(headerWidth);

            strategy.apply(
                    holder, List.of(cellData(CellDataTypeEnum.STRING, "a".repeat(300), null, null, null)), cell, false);
            assertThat(sheet.getColumnWidth(0)).isEqualTo(255 * 256);

            strategy.apply(holder, List.of(cellData(CellDataTypeEnum.BOOLEAN, null, true, null, null)), cell, false);
            strategy.apply(
                    holder, List.of(cellData(CellDataTypeEnum.NUMBER, null, null, BigDecimal.TEN, null)), cell, false);
            strategy.apply(
                    holder,
                    List.of(cellData(CellDataTypeEnum.DATE, null, null, null, LocalDateTime.of(2026, 9, 3, 3, 0))),
                    cell,
                    false);
            assertThat(sheet.getColumnWidth(0)).isEqualTo(255 * 256);
        }
    }

    @Test
    void setColumnWidth_ignoresEmptyCellsAndUnsupportedTypes() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("data");
            Cell cell = sheet.createRow(0).createCell(0);
            WriteSheetHolder holder = holder(sheet);
            ExposedStrategy strategy = new ExposedStrategy();

            strategy.apply(holder, List.of(), cell, false);
            strategy.apply(holder, List.of(cellData(null, null, null, null, null)), cell, false);
            strategy.apply(holder, List.of(cellData(CellDataTypeEnum.EMPTY, null, null, null, null)), cell, false);

            assertThat(sheet.getColumnWidth(0)).isEqualTo(sheet.getDefaultColumnWidth() * 256);
        }
    }

    @Test
    void setColumnWidth_measuresRichTextViaTextString() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("data");
            Cell cell = sheet.createRow(0).createCell(0);
            WriteSheetHolder holder = holder(sheet);
            ExposedStrategy strategy = new ExposedStrategy();

            WriteCellData<?> richText = mock(WriteCellData.class);
            when(richText.getType()).thenReturn(CellDataTypeEnum.RICH_TEXT_STRING);
            when(richText.getRichTextStringDataValue()).thenReturn(new RichTextStringData("富文本"));

            strategy.apply(holder, List.of(richText), cell, false);

            assertThat(sheet.getColumnWidth(0)).isEqualTo("富文本".getBytes().length * 256);
        }
    }

    @Test
    void setColumnWidth_richTextWithoutValueSkipsWidthCalculation() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("data");
            Cell cell = sheet.createRow(0).createCell(0);
            WriteSheetHolder holder = holder(sheet);
            ExposedStrategy strategy = new ExposedStrategy();

            WriteCellData<?> richText = mock(WriteCellData.class);
            when(richText.getType()).thenReturn(CellDataTypeEnum.RICH_TEXT_STRING);
            when(richText.getRichTextStringDataValue()).thenReturn(null);

            strategy.apply(holder, List.of(richText), cell, false);

            assertThat(sheet.getColumnWidth(0)).isEqualTo(sheet.getDefaultColumnWidth() * 256);
        }
    }

    private static WriteSheetHolder holder(Sheet sheet) {
        WriteSheetHolder holder = mock(WriteSheetHolder.class);
        when(holder.getSheetNo()).thenReturn(0);
        when(holder.getSheet()).thenReturn(sheet);
        return holder;
    }

    @SuppressWarnings("unchecked")
    private static WriteCellData<?> cellData(
            CellDataTypeEnum type, String text, Boolean bool, BigDecimal number, LocalDateTime date) {
        WriteCellData<Object> data = mock(WriteCellData.class);
        when(data.getType()).thenReturn(type);
        when(data.getStringValue()).thenReturn(text);
        when(data.getBooleanValue()).thenReturn(bool);
        when(data.getNumberValue()).thenReturn(number);
        when(data.getDateValue()).thenReturn(date);
        return data;
    }

    private static final class ExposedStrategy extends ColumnWidthMatchStyleStrategy {

        void apply(WriteSheetHolder holder, List<WriteCellData<?>> data, Cell cell, boolean isHead) {
            setColumnWidth(holder, data, cell, null, 0, isHead);
        }
    }
}
