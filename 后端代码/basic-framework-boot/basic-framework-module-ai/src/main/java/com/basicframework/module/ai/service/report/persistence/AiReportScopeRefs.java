package com.basicframework.module.ai.service.report.persistence;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.policy.AiResourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 报表版本的资源依赖与范围指纹（R04 第 4 步）。
 *
 * <p>口径（与 A03 一致，不自造第二套）：
 * <ul>
 *   <li>依赖项只认 A03 词表：{@code resourceType} 必须是 {@link AiResourceType} 的取值，
 *       未知类型**拒绝**（fail-closed），不静默跳过；</li>
 *   <li>每项依赖在保存时判定一次 READ，判定拒绝即拒绝保存；判定放行则记下该次判定的
 *       {@code scopeFingerprint}（主体范围 + 授权版本的摘要）；</li>
 *   <li>读取时逐项 {@code reauthorizeHistorical} 复核：任一项当前范围无法覆盖原范围即拒绝显示，
 *       并提示按当前权限重新生成（AT-048）；</li>
 *   <li>版本的整体指纹是各项指纹的稳定摘要（排序后拼接再哈希），用于检测依赖集合被替换。</li>
 * </ul>
 *
 * <p>为什么指纹按"项"存而不是只存整体摘要：整体摘要只能回答"变没变"，无法定位"哪一项失权"；
 * 逐项指纹让拒绝原因可诊断。
 */
public final class AiReportScopeRefs {

    /** 未声明任何资源依赖时的稳定指纹（空集合的摘要，不是空字符串）。 */
    public static final String EMPTY_FINGERPRINT = digest("");

    private AiReportScopeRefs() {}

    /**
     * 解析依赖项（来自 {@code sourcesJson}）。
     *
     * @param sourcesJson 依赖数组 JSON：{@code [{"resourceType":"DATASET","resourceKey":"dset_orders"}]}
     * @return 依赖项（保持声明顺序，按 type/key 去重）
     * @throws IllegalArgumentException 结构不合法或类型不在词表内
     */
    public static List<AiReportScopeRef> parse(String sourcesJson) {
        if (sourcesJson == null || sourcesJson.isBlank()) {
            return List.of();
        }
        List<?> items;
        try {
            items = JsonUtils.parseObject(sourcesJson, List.class);
        } catch (IllegalArgumentException notAnArray) {
            throw new IllegalArgumentException("sourcesJson 必须是数组");
        }
        if (items == null) {
            throw new IllegalArgumentException("sourcesJson 必须是数组");
        }
        List<AiReportScopeRef> refs = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> node)) {
                throw new IllegalArgumentException("依赖项必须是对象");
            }
            String type = text(node, "resourceType");
            String key = text(node, "resourceKey");
            AiResourceType resourceType = AiResourceType.parse(type)
                    .orElseThrow(() -> new IllegalArgumentException("resourceType 不在 A03 词表：" + type));
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("resourceKey 不能为空");
            }
            AiReportScopeRef ref = new AiReportScopeRef(resourceType.name(), key, null);
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private static String text(Map<?, ?> node, String key) {
        Object value = node.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 记录某依赖项本次判定的指纹。 */
    public static AiReportScopeRef withFingerprint(AiReportScopeRef ref, String fingerprint) {
        return new AiReportScopeRef(ref.getResourceType(), ref.getResourceKey(), fingerprint);
    }

    /** 依赖项的稳定键（{@code TYPE/key}）。 */
    public static String key(AiReportScopeRef ref) {
        return ref.getResourceType() + "/" + ref.getResourceKey();
    }

    /** 整体指纹：排序后的 {@code TYPE/key=fingerprint} 拼接再哈希。 */
    public static String fingerprint(List<AiReportScopeRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return EMPTY_FINGERPRINT;
        }
        StringBuilder builder = new StringBuilder();
        refs.stream().sorted(Comparator.comparing(AiReportScopeRefs::key)).forEach(ref -> builder.append(key(ref))
                .append('=')
                .append(ref.getFingerprint() == null ? "" : ref.getFingerprint())
                .append(';'));
        return digest(builder.toString());
    }

    /** 序列化（写入 {@code scope_refs_json}）。 */
    public static String toJson(List<AiReportScopeRef> refs) {
        return JsonUtils.toJsonString(refs == null ? List.of() : refs);
    }

    /** 反序列化（读取时复核）。 */
    public static List<AiReportScopeRef> fromJson(String scopeRefsJson) {
        if (scopeRefsJson == null || scopeRefsJson.isBlank()) {
            return List.of();
        }
        List<AiReportScopeRef> refs = JsonUtils.parseArray(scopeRefsJson, AiReportScopeRef.class);
        return refs == null ? List.of() : refs;
    }

    private static String digest(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
