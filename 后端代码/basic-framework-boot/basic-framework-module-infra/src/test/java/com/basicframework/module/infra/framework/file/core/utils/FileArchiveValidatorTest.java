package com.basicframework.module.infra.framework.file.core.utils;

import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_ARCHIVE_UNSAFE;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.infra.framework.file.config.FileArchiveSecurityProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

class FileArchiveValidatorTest {

    private FileArchiveSecurityProperties properties;
    private FileArchiveValidator validator;

    @BeforeEach
    void setUp() {
        properties = new FileArchiveSecurityProperties();
        validator = new FileArchiveValidator(properties);
    }

    @Test
    void validate_withSafeArchive_acceptsNestedEntries() throws IOException {
        byte[] content = zip(Map.of("documents/readme.txt", "safe"));

        assertThatCode(() -> validator.validate(content, "documents.zip")).doesNotThrowAnyException();
    }

    @Test
    void validate_withNonZipFile_skipsArchiveInspection() {
        assertThatCode(() -> validator.validate(new byte[] {1}, "image.png")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"../escape.txt", "safe/../../escape.txt", "..\\escape.txt", "/absolute.txt", "C:/drive.txt"})
    void validate_withUnsafeEntryPath_rejectsArchive(String entryName) throws IOException {
        assertUnsafe(zip(Map.of(entryName, "payload")));
    }

    @Test
    void validate_withTooManyEntries_rejectsArchive() throws IOException {
        properties.setMaxEntries(1);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("first.txt", "first");
        entries.put("second.txt", "second");

        assertUnsafe(zip(entries));
    }

    @Test
    void validate_withEmptyArchive_rejectsArchive() throws IOException {
        assertUnsafe(zip(Map.of()));
    }

    @Test
    void validate_withExpandedSizeOverLimit_rejectsArchive() throws IOException {
        properties.setMaxExpandedSize(DataSize.ofBytes(4));

        assertUnsafe(zip(Map.of("large.txt", "12345")));
    }

    @Test
    void validate_withCompressionRatioOverLimit_rejectsArchive() throws IOException {
        properties.setMaxCompressionRatio(2D);

        assertUnsafe(zip(Map.of("repeated.txt", "A".repeat(4096))));
    }

    private void assertUnsafe(byte[] content) {
        assertThatThrownBy(() -> validator.validate(content, "unsafe.zip"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(FILE_ARCHIVE_UNSAFE.getCode());
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
