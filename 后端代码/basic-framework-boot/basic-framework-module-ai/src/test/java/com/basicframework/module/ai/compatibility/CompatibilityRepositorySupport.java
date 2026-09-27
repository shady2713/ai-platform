package com.basicframework.module.ai.compatibility;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Q08 兼容/升级演练用例的仓库材料定位与内容摘要工具。
 *
 * <p>升级回归必须对准**真实仓库材料**（Flyway 迁移目录、依赖 BOM、上游台账、冻结契约样例），
 * 不能在用例里另造一套平行数据。定位方式与既有 {@code AiProtocolFixtureTest}/{@code AiTestFixtures}
 * 相同：从测试工作目录向上找到仓库根；找不到就明确失败，而不是静默跳过（否则演练会假绿）。
 *
 * <p>本类只读文件，不写任何仓库路径。
 */
final class CompatibilityRepositorySupport {

    private CompatibilityRepositorySupport() {}

    /** 仓库根：以冻结契约样例与后端依赖 BOM 同时存在为标志。 */
    static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 12 && directory != null; depth++) {
            if (Files.isDirectory(directory.resolve("docs/contracts/ai/samples"))
                    && Files.isRegularFile(
                            directory.resolve("后端代码/basic-framework-boot/basic-framework-dependencies/pom.xml"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("未找到仓库根（需含 docs/contracts/ai/samples 与后端依赖 BOM）：测试必须能到达仓库根");
    }

    /** Flyway 迁移目录（真实已执行历史与候选迁移的唯一来源；本包只读，不改历史 SQL）。 */
    static Path migrationDirectory() {
        return repositoryRoot()
                .resolve("后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration");
    }

    /** 冻结依赖版本所在 BOM。 */
    static Path dependenciesBom() {
        return repositoryRoot().resolve("后端代码/basic-framework-boot/basic-framework-dependencies/pom.xml");
    }

    /** 上游接入台账（F02 维护）。 */
    static Path upstreamRegistry() {
        return repositoryRoot().resolve("docs/integrations/upstream-registry.yaml");
    }

    /** 跨语言冻结契约样例目录。 */
    static Path contractSamplesDirectory() {
        return repositoryRoot().resolve("docs/contracts/ai/samples");
    }

    /** 冻结契约 Schema 文件。 */
    static Path contractSchema(String schemaBaseName) {
        return repositoryRoot().resolve("docs/contracts/ai/" + schemaBaseName + ".schema.json");
    }

    static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("读取仓库材料失败：" + path, exception);
        }
    }

    static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("读取仓库材料失败：" + path, exception);
        }
    }

    /** 内容摘要（SHA-256 十六进制）：升级演练用它代替 Flyway 的 CRC32 做逐文件比对。 */
    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }
}
