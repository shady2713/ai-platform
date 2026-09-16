package com.basicframework.module.infra.framework.file.core.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 文件类型校验与下载响应安全边界测试。
 *
 * 上传白名单必须同时满足扩展名和内容类型；下载时仅图片可内联，其他内容一律作为附件。
 */
class FileTypeUtilsTest {

    private static final byte[] PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlXNrAAAAAASUVORK5CYII=");
    private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void isAllowedUploadType_requiresMatchingExtensionAndDetectedContentType() throws IOException {
        assertThat(FileTypeUtils.isAllowedUploadType(PNG, "avatar.PNG")).isTrue();
        assertThat(FileTypeUtils.isAllowedUploadType("plain text".getBytes(StandardCharsets.UTF_8), "readme.TXT"))
                .isTrue();
        assertThat(FileTypeUtils.isAllowedUploadType(zip(Map.of("readme.txt", "safe")), "archive.zip"))
                .isTrue();

        assertThat(FileTypeUtils.isAllowedUploadType(PNG, "avatar.pdf")).isFalse();
        assertThat(FileTypeUtils.isAllowedUploadType(PNG, "avatar")).isFalse();
        assertThat(FileTypeUtils.isAllowedUploadType(PNG, "avatar.exe")).isFalse();
        assertThat(FileTypeUtils.isAllowedUploadType(SVG, "avatar.png")).isFalse();
    }

    @Test
    void writeAttachment_allowsInlineOnlyForDetectedImages() throws IOException {
        MockHttpServletResponse imageResponse = new MockHttpServletResponse();
        FileTypeUtils.writeAttachment(imageResponse, "avatar.png", PNG);

        assertThat(imageResponse.getContentType()).isEqualTo("image/png");
        assertThat(imageResponse.getHeader("Content-Disposition")).startsWith("inline;filename=");
        assertThat(imageResponse.getContentAsByteArray()).isEqualTo(PNG);

        byte[] text = "safe text".getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse textResponse = new MockHttpServletResponse();
        FileTypeUtils.writeAttachment(textResponse, "notes.txt", text);

        assertThat(textResponse.getContentType()).startsWith("text/plain");
        assertThat(textResponse.getHeader("Content-Disposition")).startsWith("attachment;filename=");
        assertThat(textResponse.getContentAsByteArray()).isEqualTo(text);

        MockHttpServletResponse svgResponse = new MockHttpServletResponse();
        FileTypeUtils.writeAttachment(svgResponse, "legacy.svg", SVG);

        assertThat(svgResponse.getHeader("Content-Disposition")).startsWith("attachment;filename=");
        assertThat(svgResponse.getContentAsByteArray()).isEqualTo(SVG);
    }

    @Test
    void helpers_normalizeExtensionsAndReturnSafeDefaults() {
        assertThat(FileTypeUtils.getFileExtension("REPORT.PDF")).isEqualTo("pdf");
        assertThat(FileTypeUtils.getFileExtension("no-extension")).isEmpty();
        assertThat(FileTypeUtils.getExtension("application/pdf")).isEqualTo(".pdf");
        assertThat(FileTypeUtils.isImage(null)).isFalse();
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
