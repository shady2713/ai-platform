package com.basicframework.module.ai.service.application;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemScopeDTO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨系统事实的"可核验表示"（Y01）：把授权事实压成稳定指纹，并渲染成模型可见的目录文本。
 *
 * <p>为什么两件事放在一起：它们都是"事实 → 对外表示"的**纯函数**，必须共用同一条口径——
 * 指纹用来在事后重新计算并比对（范围选择再核验），模型目录用来决定模型"知道"哪些系统。
 * 两者的输入都只来自服务端事实，输出不读时钟、不读随机数，因此同一事实在任何时刻、任何实例上
 * 得到同一结果。
 *
 * <ul>
 *   <li>{@link #sha256(String)}：SHA-256 小写十六进制摘要（模块内公用；不是对外协议）；</li>
 *   <li>{@link #render}：模型可见目录 = 结构化 JSON（键序固定），只含系统标识与资源清单，
 *       **不含**外部用户标识、范围来源与内部编号：无权系统没有进入渲染的入口，任何系统名/资源键
 *       都作为 JSON 字符串转义，不可能通过改写目录文本改变分区或指令边界。</li>
 * </ul>
 */
public final class AiCrossSystemFacts {

    private AiCrossSystemFacts() {}

    /** 计算 SHA-256 小写十六进制摘要（模块内公用；不是对外协议）。 */
    public static String sha256(String value) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            // JDK 必备算法缺失属于环境损坏，不能降级成"没有指纹"
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 渲染为稳定的 JSON 文本（无条目时为 {@code []}）。 */
    public static String render(List<AiSystemEntryDTO> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiSystemEntryDTO entry : entries == null ? List.<AiSystemEntryDTO>of() : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("system", entry.getAppCode());
            row.put("current", entry.isCurrentSystem());
            row.put("resources", resources(entry.getScopes()));
            rows.add(row);
        }
        return JsonUtils.toJsonString(rows);
    }

    private static List<Map<String, Object>> resources(List<AiSystemScopeDTO> scopes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiSystemScopeDTO scope : scopes == null ? List.<AiSystemScopeDTO>of() : scopes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", scope.getResourceType());
            row.put("key", scope.getResourceKey());
            row.put("actions", scope.getActions());
            rows.add(row);
        }
        return rows;
    }
}
