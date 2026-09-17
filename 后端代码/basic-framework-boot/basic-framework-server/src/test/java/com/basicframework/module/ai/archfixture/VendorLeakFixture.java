package com.basicframework.module.ai.archfixture;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 违例夹具：provider.springai 之外的类引用 Spring AI 厂商类型，用于证明规则 H 能变红。
 *
 * <p>参考 M02 落地前，本夹具是 vendor 规则唯一的可执行反例。
 */
public class VendorLeakFixture {

    private ChatModel chatModel;
}
