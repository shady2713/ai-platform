package com.basicframework.framework.excel.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.hutool.extra.spring.SpringUtil;
import cn.idev.excel.annotation.ExcelIgnore;
import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.idev.excel.write.metadata.holder.WriteSheetHolder;
import cn.idev.excel.write.metadata.holder.WriteWorkbookHolder;
import com.basicframework.framework.dict.core.DictFrameworkUtils;
import com.basicframework.framework.excel.core.annotations.ExcelColumnSelect;
import com.basicframework.framework.excel.core.function.ExcelColumnSelectFunction;
import com.basicframework.module.system.api.dict.DictDataCommonApi;
import com.basicframework.module.system.api.dict.dto.DictDataRespDTO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

class SelectSheetWriteHandlerTest {

    private static final String DICT_TYPE = "select-status";

    @BeforeEach
    void setUp() {
        DictDataRespDTO first = entry("启用", "1");
        DictDataRespDTO second = entry("停用", "0");
        DictDataCommonApi api = mock(DictDataCommonApi.class);
        when(api.getDictDataList(DICT_TYPE)).thenReturn(List.of(first, second));
        DictFrameworkUtils.init(api, Duration.ofMinutes(1));
    }

    @Test
    void afterSheetCreate_writesHiddenDictionaryAndValidationConstraint() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet dataSheet = workbook.createSheet("data");
            SelectSheetWriteHandler handler = new SelectSheetWriteHandler(AnnotatedHead.class);

            handler.afterSheetCreate(workbookHolder(workbook), sheetHolder(dataSheet));

            Sheet dictSheet = workbook.getSheet("字典sheet");
            assertThat(dictSheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("启用");
            assertThat(dictSheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("停用");
            assertThat(workbook.getName("dict2").getRefersToFormula()).isEqualTo("字典sheet!$C$1:$C$2");
            assertThat(dataSheet.getDataValidations()).hasSize(1);
            assertThat(dataSheet.getDataValidations().get(0).getErrorStyle())
                    .isEqualTo(org.apache.poi.ss.usermodel.DataValidation.ErrorStyle.STOP);
        }
    }

    @Test
    void afterSheetCreate_withoutSelectColumnsDoesNotCreateDictionarySheet() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet dataSheet = workbook.createSheet("data");

            new SelectSheetWriteHandler(EmptyHead.class)
                    .afterSheetCreate(workbookHolder(workbook), sheetHolder(dataSheet));

            assertThat(workbook.getSheet("字典sheet")).isNull();
        }
    }

    @Test
    void constructor_rejectsSelectionWithoutDictionaryOrFunction() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SelectSheetWriteHandler(InvalidHead.class));
    }

    @Test
    void constructor_resolvesSelectsFromRegisteredSpringFunction() throws Exception {
        Map<String, ExcelColumnSelectFunction> functions = Map.of("statusOptions", new ExcelColumnSelectFunction() {
            @Override
            public String getName() {
                return "statusOptions";
            }

            @Override
            public List<String> getOptions() {
                return List.of("在线", "离线");
            }
        });
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(ExcelColumnSelectFunction.class)).thenReturn(functions);

        try (MockedStatic<SpringUtil> springUtil = Mockito.mockStatic(SpringUtil.class)) {
            springUtil.when(SpringUtil::getApplicationContext).thenReturn(context);

            SelectSheetWriteHandler handler = new SelectSheetWriteHandler(FunctionHead.class);
            try (XSSFWorkbook workbook = new XSSFWorkbook()) {
                Sheet dataSheet = workbook.createSheet("data");
                handler.afterSheetCreate(workbookHolder(workbook), sheetHolder(dataSheet));

                Sheet dictSheet = workbook.getSheet("字典sheet");
                assertThat(dictSheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("在线");
                assertThat(dictSheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("离线");
            }
        }
    }

    @Test
    void afterSheetCreate_supportsHssfWorkbookArrowSuppression() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet dataSheet = workbook.createSheet("data");
            SelectSheetWriteHandler handler = new SelectSheetWriteHandler(AnnotatedHead.class);

            handler.afterSheetCreate(workbookHolder(workbook), sheetHolder(dataSheet));

            assertThat(workbook.getSheet("字典sheet")).isNotNull();
            assertThat(dataSheet.getDataValidations()).hasSize(1);
        }
    }

    private static WriteWorkbookHolder workbookHolder(Workbook workbook) {
        WriteWorkbookHolder holder = mock(WriteWorkbookHolder.class);
        when(holder.getWorkbook()).thenReturn(workbook);
        return holder;
    }

    private static WriteSheetHolder sheetHolder(Sheet sheet) {
        WriteSheetHolder holder = mock(WriteSheetHolder.class);
        when(holder.getSheet()).thenReturn(sheet);
        return holder;
    }

    private static DictDataRespDTO entry(String label, String value) {
        DictDataRespDTO entry = new DictDataRespDTO();
        entry.setLabel(label);
        entry.setValue(value);
        return entry;
    }

    @ExcelIgnoreUnannotated
    private static final class AnnotatedHead {

        private static final String CONSTANT = "constant";

        @ExcelIgnore
        private String ignored;

        private transient String transientValue;

        @ExcelProperty(value = "状态", index = 2)
        @ExcelColumnSelect(dictType = DICT_TYPE)
        private String status;
    }

    private static final class EmptyHead {
        private String value;
    }

    private static final class FunctionHead {
        @ExcelProperty(value = "状态", index = 1)
        @ExcelColumnSelect(functionName = "statusOptions")
        private String status;
    }

    private static final class InvalidHead {
        @ExcelColumnSelect
        private String value;
    }
}
