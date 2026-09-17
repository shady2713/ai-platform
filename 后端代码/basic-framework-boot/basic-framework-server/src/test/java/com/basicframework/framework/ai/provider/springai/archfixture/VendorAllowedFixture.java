package com.basicframework.framework.ai.provider.springai.archfixture;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 合规反例（负向对照）：provider.springai 包内允许引用 Spring AI 类型，
 * 不能产生规则 H 的违例；与 {@code com.basicframework.module.ai.archfixture.VendorLeakFixture}
 * 成对使用，证明厂商类型边界按包放行而非全局禁止。
 */
public class VendorAllowedFixture {

    private ChatModel chatModel;
}
