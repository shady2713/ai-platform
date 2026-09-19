package com.basicframework.module.ai.domain.serviceconfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * 发布内容摘要（S02）：把发布版本的全部**可评测内容**折叠成稳定摘要。
 *
 * <p>摘要覆盖：模型端点、端点配置版本、提示词、输入/输出 Schema、所需能力、评测门槛与
 * 冻结的资源绑定集合。评测结论与回退判定都以它为身份：只要任一内容项变化，摘要即变化，
 * 旧评测报告对新内容不再有效（"不能借旧报告发布新内容"）。
 *
 * <p>摘要是十六进制 SHA-256，不含任何凭据；资源绑定按（类型, 标识, 动作）排序后参与计算，
 * 绑定顺序不影响摘要。
 */
public final class AiServiceContentHash {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AiServiceContentHash() {}

    /**
     * 计算发布内容摘要。
     *
     * @param modelEndpointId       模型端点编号
     * @param endpointConfigRevision 端点配置版本
     * @param promptTemplate        提示词模板
     * @param inputSchema           输入 Schema
     * @param outputSchema          输出 Schema
     * @param requiredCapabilities  所需能力（逗号分隔的稳定集合）
     * @param evalThreshold         评测门槛
     * @param resources             冻结的资源绑定（类型, 标识, 动作集合）
     */
    public static String compute(
            Long modelEndpointId,
            Integer endpointConfigRevision,
            String promptTemplate,
            String inputSchema,
            String outputSchema,
            String requiredCapabilities,
            Integer evalThreshold,
            List<ResourceBinding> resources) {
        StringBuilder canonical = new StringBuilder(512);
        canonical.append("endpoint=").append(modelEndpointId).append('\n');
        canonical.append("configRevision=").append(endpointConfigRevision).append('\n');
        canonical.append("threshold=").append(evalThreshold).append('\n');
        canonical.append("capabilities=").append(requiredCapabilities).append('\n');
        canonical.append("prompt=").append(promptTemplate).append('\n');
        canonical.append("input=").append(inputSchema).append('\n');
        canonical
                .append("output=")
                .append(outputSchema == null ? "" : outputSchema)
                .append('\n');
        resources.stream()
                .map(binding -> binding.resourceType() + ":" + binding.resourceKey() + ":"
                        + normalizeActions(binding.actions()))
                .sorted()
                .forEach(line -> canonical.append("resource=").append(line).append('\n'));
        return sha256Hex(canonical.toString());
    }

    /** 动作集合规范化：与绑定顺序无关（READ,EXECUTE 与 EXECUTE,READ 摘要相同）。 */
    private static String normalizeActions(String actions) {
        if (actions == null || actions.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(actions.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte current : hashed) {
                hex.append(HEX[(current >> 4) & 0xF]).append(HEX[current & 0xF]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 参与摘要的冻结资源绑定（动作集合须为规范化后的逗号分隔字符串）。 */
    public record ResourceBinding(String resourceType, String resourceKey, String actions) {

        public ResourceBinding {
            Objects.requireNonNull(resourceType, "resourceType");
            Objects.requireNonNull(resourceKey, "resourceKey");
            Objects.requireNonNull(actions, "actions");
        }
    }
}
