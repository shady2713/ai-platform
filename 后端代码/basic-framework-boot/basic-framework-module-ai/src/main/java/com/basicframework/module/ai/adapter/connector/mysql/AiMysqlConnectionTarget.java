package com.basicframework.module.ai.adapter.connector.mysql;

import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 只读连接目标（D03）：由服务层从连接器行 + 已校验配置 + 解密后的秘密组装。
 *
 * <p>这是"独立连接池"的唯一输入：池按 {@link #poolKey()} 复用，
 * 连接相关字段（地址）或凭据版本变化都会换池，配置里与连接无关的变化（如白名单）不换池。
 *
 * <p>秘密只以 {@code password} 字段存在，不进 {@code toString()}、不进日志、不进异常。
 */
public record AiMysqlConnectionTarget(
        Long connectorId,
        String jdbcUrl,
        String database,
        String username,
        String password,
        List<String> allowedObjects,
        int credentialRevision) {

    public AiMysqlConnectionTarget {
        if (connectorId == null || !StringUtils.hasText(jdbcUrl) || !StringUtils.hasText(username)) {
            throw new IllegalArgumentException("只读连接目标缺少必要字段");
        }
        allowedObjects = allowedObjects == null ? List.of() : List.copyOf(allowedObjects);
    }

    /** 池键：连接器 + 凭据版本 + 地址指纹（不含任何凭据内容）。 */
    public String poolKey() {
        return connectorId + "#" + credentialRevision + "#" + Integer.toHexString(jdbcUrl.hashCode());
    }

    /** 目标对象是否在授权白名单内（schema 与对象名都不区分大小写；默认拒绝）。 */
    public boolean authorizes(String schema, String object) {
        if (!StringUtils.hasText(schema) || !StringUtils.hasText(object)) {
            return false;
        }
        String normalized = (schema + "." + object).toLowerCase(Locale.ROOT);
        return allowedObjects.contains(normalized);
    }

    @Override
    public String toString() {
        return "AiMysqlConnectionTarget[connectorId=" + connectorId + ", database=" + database + ", revision="
                + credentialRevision + ", objects=" + allowedObjects.size() + "]";
    }
}
