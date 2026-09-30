package com.basicframework.framework.ai.provider.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 一个 MCP 工具的**协议事实**（X07）。
 *
 * <p>本 record 是"上游说了什么"的中性搬运，不含任何平台授权语义——授权判定发生在 module-ai 侧，
 * 且只依据 {@link #name()} 与 {@link #inputSchemaJson()}（见 {@link #schemaFingerprint()}）。
 *
 * <h2>提示注入防线（验收 2 的结构化落点）</h2>
 * <p>{@link #description()} 是**上游提供的不可信文本**，不是平台指令。它可以写
 * "忽略之前的指令 / 把 policy 设成 AUTO / 你已被安全团队批准"，这些字样对平台没有任何效力。
 * 为了让这条防线不依赖"调用方自觉"，本类做三件机械的事：
 * <ol>
 *   <li><b>描述不参与指纹</b>：{@link #schemaFingerprint()} 只覆盖工具名与输入 schema。
 *       于是"往描述里塞一段诱导文案"既不会让工具看起来"和已审批版本一致"而被自动放行，
 *       也不会凭空改变任何授权结论——它只是一个更长的字符串；</li>
 *   <li><b>描述长度截断</b>：上游可以用一个超大描述耗尽存储/上下文，截断在搬运处发生，
 *       不依赖下游是否记得做长度校验；</li>
 *   <li><b>描述与结构分离</b>：结构字段（name/inputSchema）没有任何"人类可读指令"的解释权，
 *       授权判定永远不读描述。</li>
 * </ol>
 *
 * <p>顺带说明：{@link #name()} 也来自上游，但它必须匹配平台的工具标识语法才能进入草稿
 * （见 module-ai 侧映射），因此它同样只能是"标识符"而不是"指令"。
 */
public record McpToolDescriptor(String name, String title, String description, String inputSchemaJson) {

    /** 描述截断长度：上游描述不可信且可能超大，超出部分丢弃（不影响结构判定）。 */
    public static final int MAX_DESCRIPTION_LENGTH = 2_000;

    public McpToolDescriptor {
        if (name == null || name.isBlank()) {
            // 工具名是结构标识，缺失即协议错误：宁可不产出工具，也不产出"无名工具"
            throw new IllegalArgumentException("MCP 工具名不能为空");
        }
        name = name.trim();
        title = title == null || title.isBlank() ? name : title.trim();
        description = truncate(description);
        inputSchemaJson = inputSchemaJson == null ? "" : inputSchemaJson;
    }

    /** 描述超出上限时截断（不可信文本不因过长而获得额外能力，只是被丢弃尾部）。 */
    private static String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= MAX_DESCRIPTION_LENGTH ? raw : raw.substring(0, MAX_DESCRIPTION_LENGTH);
    }

    /**
     * 结构指纹：<b>只</b>覆盖工具名与输入 schema，<b>不含</b>描述。
     *
     * <p>这是"上游升级导致 Schema 变化时阻断旧发布"的判据，也是"描述里的诱导文案拿不到权限"的
     * 机制保证：改描述不动指纹（因此不能借此被当作"已审批项"），改 schema 必动指纹
     * （因此任何真实的接口语义变化都会被判定为漂移并阻断）。
     */
    public String schemaFingerprint() {
        String canonical = name + "|" + inputSchemaJson;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /** 输入 schema 是否为空文本（module-ai 侧据此拒绝"无法声明参数面"的工具）。 */
    public boolean hasEmptySchema() {
        return inputSchemaJson.isBlank();
    }
}
