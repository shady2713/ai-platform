/**
 * Spring AI 唯一引用区域。
 *
 * <p>本包是全仓库唯一允许直接引用 {@code org.springframework.ai} 类型的区域：在此实现
 * {@link com.basicframework.framework.ai.core.model.ModelPort} 并把厂商类型转译为自有契约。
 * 其他包引用厂商类型会被模块边界门禁的 vendor 规则拒绝。
 *
 * <p>F03 只落地包声明与边界约束；提供方实现（受管客户端工厂、协议适配）随 M02/M03 任务补入。
 */
package com.basicframework.framework.ai.provider.springai;
