package com.basicframework.module.ai.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 合成业务与协议测试夹具装载器（F10）。
 *
 * <p>夹具的**唯一来源**是 {@code packages/ai-contracts/fixtures}（与前端共用同一份文件），
 * 本类只做定位与解析，不在 Java 侧复制样例，避免两栈各自维护导致漂移。
 * 夹具只用于协议与失败路径验证，不能用 mock 证明真实模型效果。
 */
public final class AiTestFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path FIXTURES_DIR = locate();

    /** 固定时钟（fixtures/fixed-clock.json 的 now）：用例不得使用系统当前时间。 */
    public static final Instant FIXED_NOW = Instant.parse("2026-09-17T02:00:00Z");

    private static Path locate() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 10 && dir != null; depth++) {
            Path candidate = dir.resolve("前端代码/basic-framework-admin/packages/ai-contracts/fixtures");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到共享夹具目录 packages/ai-contracts/fixtures");
    }

    /** 读取指定夹具文件为 JSON。 */
    public static JsonNode load(String name) {
        try {
            return MAPPER.readTree(Files.readString(FIXTURES_DIR.resolve(name)));
        } catch (IOException exception) {
            throw new UncheckedIOException("读取夹具失败：" + name, exception);
        }
    }

    /** 读取夹具中的数组字段。 */
    public static ArrayNode rows(String name, String arrayField) {
        JsonNode node = load(name).path(arrayField);
        if (!node.isArray()) {
            throw new IllegalStateException("夹具 " + name + " 缺少数组字段 " + arrayField);
        }
        return (ArrayNode) node;
    }

    /** 解析时间戳；夹具中的时间必须可被 {@link Instant#parse} 接受。 */
    public static Instant parseInstant(String value) {
        return Instant.parse(value);
    }

    /** 判断字符串是否为十进制金额（协议要求金额用字符串，避免浮点精度损失）。 */
    public static boolean isDecimalString(String value) {
        return value != null && value.matches("^-?\\d+(\\.\\d+)?$");
    }

    private AiTestFixtures() {}
}
