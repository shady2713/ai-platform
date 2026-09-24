package com.basicframework.module.ai.controller.app.v1.embed;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 嵌入产物清单（C05）：按**清单**提供静态资产，不做目录遍历。
 *
 * <p>为什么不是简单的静态资源映射：嵌入页是公开路径，直接把一个目录挂到 URL 上等于把所有放进去的东西
 * （备份、源映射、部署脚本）都暴露出去。这里改成"清单即白名单"：
 * 只有 `asset-manifest.json` 声明过的文件名可以被取，且文件名必须是单段（不含分隔符）——
 * 目录穿越在**清单校验**与**文件名校验**两处都不可能发生。
 *
 * <p>清单缺失时视为"产物未就位"（调用方给出 503 与稳定错误码），不返回一个加载不出脚本的空壳。
 */
@Component
@RequiredArgsConstructor
public class AiEmbedAssetCatalog {

    private static final String MANIFEST_NAME = "asset-manifest.json";

    private static final String ASSET_DIRECTORY_NAME = "assets";

    private final AiEmbedProperties properties;

    /** 一次服务所需的全部信息：入口脚本、入口样式与白名单。 */
    public record Manifest(String entryCss, String entryJs, Map<String, Asset> files) {}

    /** 单个资产：磁盘路径与声明的大小（用于 ETag 与上限校验）。 */
    public record Asset(long size, Path path) {}

    /** 读取并校验清单；未就位或格式非法时返回 null（调用方决定错误码，不在这里抛业务异常）。 */
    public Manifest load() {
        Path directory = Path.of(properties.getAssetsDirectory());
        Path manifestPath = directory.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifestPath)) {
            return null;
        }
        try {
            Map<String, Object> root = JsonUtils.parseObject(Files.readString(manifestPath), Map.class);
            if (root == null || !(root.get("files") instanceof Map<?, ?> rawFiles)) {
                return null;
            }
            Map<String, Asset> files = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawFiles.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !isSafeAssetName(name)) {
                    return null;
                }
                Path path =
                        directory.resolve(ASSET_DIRECTORY_NAME).resolve(name).normalize();
                if (!path.startsWith(directory.resolve(ASSET_DIRECTORY_NAME).normalize())
                        || !Files.isRegularFile(path)) {
                    return null;
                }
                long size = Files.size(path);
                if (size > properties.getMaxAssetBytes()) {
                    return null;
                }
                files.put(name, new Asset(size, path));
            }
            if (files.isEmpty()) {
                return null;
            }
            String entryJs = requireEntry(root.get("entryJs"), files);
            String entryCss = optionalEntry(root.get("entryCss"), files);
            if (entryJs == null) {
                return null;
            }
            return new Manifest(entryCss, entryJs, Map.copyOf(files));
        } catch (IOException | RuntimeException exception) {
            // 清单不可读/不可解析：按"未就位"处理（调用方 503），不把目录内容泄漏给调用方
            return null;
        }
    }

    /** 读取资产内容（清单已校验过路径与上限）。 */
    public byte[] read(Asset asset) throws IOException {
        return Files.readAllBytes(asset.path());
    }

    /** 资产文件名必须是单段、无穿越、无隐藏前缀的普通文件名。 */
    static boolean isSafeAssetName(String name) {
        if (name.isEmpty() || name.length() > 128) {
            return false;
        }
        if (name.startsWith(".") || name.contains("/") || name.contains("\\")) {
            return false;
        }
        return name.matches("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    }

    private static String requireEntry(Object value, Map<String, Asset> files) {
        if (value instanceof String name && files.containsKey(name)) {
            return name;
        }
        return null;
    }

    private static String optionalEntry(Object value, Map<String, Asset> files) {
        if (value instanceof String name && files.containsKey(name)) {
            return name;
        }
        return null;
    }

    /** 允许的文件后缀 → 响应媒体类型（未列出的后缀按 octet-stream，不猜测可执行类型）。 */
    public static String contentType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (extension) {
            case "css" -> "text/css";
            case "js", "mjs" -> "text/javascript";
            case "json" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "woff2" -> "font/woff2";
            case "woff" -> "font/woff";
            case "png" -> "image/png";
            default -> "application/octet-stream";
        };
    }

    /** 清单里声明的文件名（供测试与部署自检使用）。 */
    public static List<String> manifestKeys(Manifest manifest) {
        return List.copyOf(manifest.files().keySet());
    }
}
