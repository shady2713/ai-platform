package com.basicframework.module.infra.framework.file.core.client.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.io.IORuntimeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LocalFileClient} 的单元测试
 *
 * 覆盖 docs/security/threat-model.md T4 的路径边界：
 * 归一化后仍位于 basePath 外的路径一律抛业务异常。
 */
class LocalFileClientTest {

    private static final Long CONFIG_ID = 1L;
    private static final String DOMAIN = "http://localhost:48080";
    private static final String CONTENT_TYPE = "text/plain";

    @TempDir
    private Path tempDir;

    private Path baseDir;
    private LocalFileClient client;

    @BeforeEach
    void setUp() {
        baseDir = tempDir.resolve("base");
        LocalFileClientConfig config = new LocalFileClientConfig();
        config.setBasePath(baseDir.toString());
        config.setDomain(DOMAIN);
        client = new LocalFileClient(CONFIG_ID, config);
    }

    // ========== 正常路径 ==========

    @Test
    void upload_normalPath_writesUnderBasePathAndReturnsFormattedUrl() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        String url = client.upload(content, "a/b.txt", CONTENT_TYPE);

        assertThat(baseDir.resolve("a/b.txt")).hasBinaryContent(content);
        assertThat(url).isEqualTo(DOMAIN + "/admin-api/infra/file/" + CONFIG_ID + "/get/a/b.txt");
    }

    @Test
    void getContent_missingFile_returnsNull() {
        assertThat(client.getContent("no-such-file.txt")).isNull();
    }

    @Test
    void getContent_existingFile_returnsStoredBytes() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        client.upload(content, "a/readme.txt", CONTENT_TYPE);

        assertThat(client.getContent("a/readme.txt")).isEqualTo(content);
    }

    @Test
    void delete_existingFile_removesOnlyTheRequestedFile() {
        client.upload("delete".getBytes(StandardCharsets.UTF_8), "a/delete.txt", CONTENT_TYPE);
        client.upload("keep".getBytes(StandardCharsets.UTF_8), "a/keep.txt", CONTENT_TYPE);

        client.delete("a/delete.txt");

        assertThat(baseDir.resolve("a/delete.txt")).doesNotExist();
        assertThat(baseDir.resolve("a/keep.txt")).hasContent("keep");
    }

    @Test
    void getContent_ioFailure_isNotMistakenForMissingFile() throws IOException {
        Files.createDirectories(baseDir.resolve("directory"));

        assertThatThrownBy(() -> client.getContent("directory")).isInstanceOf(IORuntimeException.class);
    }

    // ========== 路径穿越防护 ==========

    @Test
    void upload_pathTraversal_rejected() {
        // 修复后允许抛 IllegalArgumentException / ServiceException 等运行时异常，且不得写出 basePath 之外
        assertThatThrownBy(() -> client.upload("x".getBytes(StandardCharsets.UTF_8), "../escape.txt", CONTENT_TYPE))
                .isInstanceOf(RuntimeException.class);
        assertThat(tempDir.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void upload_absolutePathTraversal_rejected() {
        Path outsideFile = tempDir.resolve("outside/escape.txt").toAbsolutePath();

        assertThatThrownBy(
                        () -> client.upload("x".getBytes(StandardCharsets.UTF_8), outsideFile.toString(), CONTENT_TYPE))
                .isInstanceOf(RuntimeException.class);
        assertThat(outsideFile).doesNotExist();
    }

    @Test
    void getContent_pathTraversal_rejected() {
        assertThatThrownBy(() -> client.getContent("../secret.txt")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void delete_pathTraversal_rejected() {
        assertThatThrownBy(() -> client.delete("../victim.txt")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void upload_symbolicLinkTraversal_rejected() throws IOException {
        Files.createDirectories(baseDir);
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Path link = baseDir.resolve("link");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            Assumptions.abort("当前文件系统不允许创建符号链接");
        }

        assertThatThrownBy(() -> client.upload("x".getBytes(StandardCharsets.UTF_8), "link/escape.txt", CONTENT_TYPE))
                .isInstanceOf(RuntimeException.class);
        assertThat(outsideDir.resolve("escape.txt")).doesNotExist();
    }
}
