package com.basicframework.module.ai.controller.app.v1.embed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C05 资产清单：清单即白名单，目录穿越与清单外路径都不可达。 */
class AiEmbedAssetCatalogTest {

    private static AiEmbedAssetCatalog catalogOf(Path directory, long maxBytes) {
        AiEmbedProperties properties = new AiEmbedProperties();
        properties.setAssetsDirectory(directory.toString());
        properties.setMaxAssetBytes(maxBytes);
        return new AiEmbedAssetCatalog(properties);
    }

    private static void writeManifest(Path directory, String json) throws IOException {
        Files.createDirectories(directory.resolve("assets"));
        Files.writeString(directory.resolve("asset-manifest.json"), json);
    }

    private static void writeAsset(Path directory, String name, String content) throws IOException {
        Files.createDirectories(directory.resolve("assets"));
        Files.writeString(directory.resolve("assets").resolve(name), content);
    }

    @Test
    void loadsManifestAndResolvesAssetsInsideAssetsDirectory(@TempDir Path directory) throws IOException {
        writeAsset(directory, "entry-a1.js", "console.log('a')");
        writeAsset(directory, "entry-a1.css", "body{}");
        writeManifest(
                directory,
                "{\"version\":1,\"entryJs\":\"entry-a1.js\",\"entryCss\":\"entry-a1.css\","
                        + "\"files\":{\"entry-a1.js\":1,\"entry-a1.css\":1}}");

        AiEmbedAssetCatalog.Manifest manifest = catalogOf(directory, 1024).load();

        assertThat(manifest).isNotNull();
        assertThat(manifest.entryJs()).isEqualTo("entry-a1.js");
        assertThat(manifest.entryCss()).isEqualTo("entry-a1.css");
        assertThat(AiEmbedAssetCatalog.manifestKeys(manifest)).containsExactlyInAnyOrder("entry-a1.js", "entry-a1.css");
    }

    @Test
    void missingManifestMeansAssetsNotStaged(@TempDir Path directory) {
        assertThat(catalogOf(directory, 1024).load()).isNull();
    }

    @Test
    void manifestOutsideWhitelistOrWithUnsafeNamesIsRejected(@TempDir Path directory) throws IOException {
        // 目录穿越：清单里写 ../ 也不行
        writeManifest(directory, "{\"entryJs\":\"../secret.js\",\"files\":{\"../secret.js\":1}}");
        assertThat(catalogOf(directory, 1024).load()).isNull();

        // 隐藏文件与服务端源映射不对外
        writeManifest(directory, "{\"entryJs\":\".env\",\"files\":{\".env\":1}}");
        assertThat(catalogOf(directory, 1024).load()).isNull();

        // 清单为空
        writeManifest(directory, "{\"entryJs\":\"a.js\",\"files\":{}}");
        assertThat(catalogOf(directory, 1024).load()).isNull();

        // 清单声明的文件磁盘上不存在（部署不完整）
        writeManifest(directory, "{\"entryJs\":\"ghost.js\",\"files\":{\"ghost.js\":1}}");
        assertThat(catalogOf(directory, 1024).load()).isNull();

        // 入口脚本不在清单内
        writeAsset(directory, "entry.js", "x");
        writeManifest(directory, "{\"entryJs\":\"other.js\",\"files\":{\"entry.js\":1}}");
        assertThat(catalogOf(directory, 1024).load()).isNull();
    }

    @Test
    void assetsAboveSizeLimitAreRejected(@TempDir Path directory) throws IOException {
        writeAsset(directory, "big.js", "0123456789");
        writeManifest(directory, "{\"entryJs\":\"big.js\",\"files\":{\"big.js\":1}}");

        assertThat(catalogOf(directory, 1024).load()).isNotNull();
        assertThat(catalogOf(directory, 8).load()).isNull();
    }

    @Test
    void assetNameRulesRejectTraversalAndHiddenFiles() {
        assertThat(AiEmbedAssetCatalog.isSafeAssetName("entry-a1.js")).isTrue();
        assertThat(AiEmbedAssetCatalog.isSafeAssetName("vendor.vue-a1.mjs")).isTrue();
        assertThat(AiEmbedAssetCatalog.isSafeAssetName("../x.js")).isFalse();
        assertThat(AiEmbedAssetCatalog.isSafeAssetName("a/b.js")).isFalse();
        assertThat(AiEmbedAssetCatalog.isSafeAssetName("a\\b.js")).isFalse();
        assertThat(AiEmbedAssetCatalog.isSafeAssetName(".env")).isFalse();
        assertThat(AiEmbedAssetCatalog.isSafeAssetName("")).isFalse();
        assertThat(AiEmbedAssetCatalog.isSafeAssetName("a".repeat(129))).isFalse();
    }

    @Test
    void contentTypeIsExplicitAndNeverGuessed() {
        assertThat(AiEmbedAssetCatalog.contentType("entry.js")).isEqualTo("text/javascript");
        assertThat(AiEmbedAssetCatalog.contentType("entry.mjs")).isEqualTo("text/javascript");
        assertThat(AiEmbedAssetCatalog.contentType("entry.css")).isEqualTo("text/css");
        assertThat(AiEmbedAssetCatalog.contentType("font.woff2")).isEqualTo("font/woff2");
        assertThat(AiEmbedAssetCatalog.contentType("logo.svg")).isEqualTo("image/svg+xml");
        assertThat(AiEmbedAssetCatalog.contentType("noext")).isEqualTo("application/octet-stream");
        assertThat(AiEmbedAssetCatalog.contentType("weird.exe")).isEqualTo("application/octet-stream");
    }

    @Test
    void readReturnsExactBytes(@TempDir Path directory) throws IOException {
        writeAsset(directory, "entry-a1.js", "console.log(1)");
        writeManifest(directory, "{\"entryJs\":\"entry-a1.js\",\"files\":{\"entry-a1.js\":1}}");
        AiEmbedAssetCatalog catalog = catalogOf(directory, 1024);

        Map<String, AiEmbedAssetCatalog.Asset> files = catalog.load().files();

        assertThat(new String(catalog.read(files.get("entry-a1.js")))).isEqualTo("console.log(1)");
    }
}
