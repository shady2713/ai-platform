package com.basicframework.module.infra.framework.file.core.utils;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.infra.enums.ErrorCodeConstants.FILE_ARCHIVE_UNSAFE;

import cn.hutool.core.util.StrUtil;
import com.basicframework.module.infra.framework.file.config.FileArchiveSecurityProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 上传 ZIP 的结构与展开规模校验器。 */
public class FileArchiveValidator {

    private static final int READ_BUFFER_SIZE = 8192;

    private final FileArchiveSecurityProperties properties;

    public FileArchiveValidator(FileArchiveSecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * ZIP 文件写入存储前执行流式校验；其他文件类型不处理。
     *
     * @param content 文件内容
     * @param fileName 文件名
     */
    public void validate(byte[] content, String fileName) {
        if (!StrUtil.equals(FileTypeUtils.getFileExtension(fileName), "zip")) {
            return;
        }
        try {
            validateZip(content);
        } catch (IOException | IllegalArgumentException invalidArchive) {
            throw exception(FILE_ARCHIVE_UNSAFE);
        }
    }

    private void validateZip(byte[] content) throws IOException {
        int entryCount = 0;
        long expandedBytes = 0;
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > properties.getMaxEntries() || isUnsafeEntryName(entry.getName())) {
                    throw exception(FILE_ARCHIVE_UNSAFE);
                }
                int read;
                while ((read = zipInputStream.read(buffer)) != -1) {
                    expandedBytes += read;
                    validateExpandedSize(content.length, expandedBytes);
                }
                zipInputStream.closeEntry();
            }
        }
        if (entryCount == 0) {
            throw exception(FILE_ARCHIVE_UNSAFE);
        }
    }

    private void validateExpandedSize(int compressedBytes, long expandedBytes) {
        if (expandedBytes > properties.getMaxExpandedSize().toBytes()
                || (double) expandedBytes / compressedBytes > properties.getMaxCompressionRatio()) {
            throw exception(FILE_ARCHIVE_UNSAFE);
        }
    }

    private static boolean isUnsafeEntryName(String entryName) {
        if (StrUtil.isBlank(entryName)
                || StrUtil.startWithAny(entryName, "/", "\\")
                || StrUtil.contains(entryName, ":")
                || entryName.chars().anyMatch(Character::isISOControl)) {
            return true;
        }
        String normalized = entryName.replace('\\', '/');
        for (String segment : normalized.split("/")) {
            if (StrUtil.equals(segment, "..")) {
                return true;
            }
        }
        return false;
    }
}
