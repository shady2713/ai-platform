package com.basicframework.module.ai.domain.policy;

import java.util.Locale;
import java.util.Optional;

/** 资源动作白名单（A03）：授权判定只认这三个动作，未列入的动作一律拒绝。 */
public enum AiAction {

    /** 读取资源（报表/知识库/文件内容）。 */
    READ,

    /** 执行资源（运行、工具调用）。 */
    EXECUTE,

    /** 导出资源（下载、外部投递）。 */
    EXPORT;

    /** 解析动作名；未知动作返回空（调用方按拒绝处理）。 */
    public static Optional<AiAction> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
