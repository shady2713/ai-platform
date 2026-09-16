package com.basicframework.framework.excel.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.annotation.ExcelProperty;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

class ExcelUtilsTest {

    @Test
    void write_generatesWorkbookAndSetsDownloadHeadersAfterSuccess() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ExcelUtils.write(response, "用户.xlsx", "用户", RowData.class, List.of(new RowData(9007199254740993L, "Alice")));

        assertThat(response.getContentAsByteArray()).isNotEmpty();
        assertThat(response.getContentType()).isEqualTo("application/vnd.ms-excel;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment;filename=%E7%94%A8%E6%88%B7.xlsx");
    }

    @Test
    void read_closesInputAndReturnsTypedRows() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FastExcelFactory.write(output, RowData.class).sheet("users").doWrite(List.of(new RowData(7L, "Alice")));
        MockMultipartFile file = new MockMultipartFile("file", "users.xlsx", null, output.toByteArray());

        List<RowData> rows = ExcelUtils.read(file, RowData.class);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(7L);
            assertThat(row.getName()).isEqualTo("Alice");
        });
    }

    public static class RowData {

        @ExcelProperty("编号")
        private Long id;

        @ExcelProperty("姓名")
        private String name;

        public RowData() {}

        RowData(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
