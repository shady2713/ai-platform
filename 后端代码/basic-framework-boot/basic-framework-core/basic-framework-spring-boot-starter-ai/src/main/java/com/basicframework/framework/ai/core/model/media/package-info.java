/**
 * 多模态媒体契约（X01 冻结）：图片理解/OCR/生成/编辑、非实时 STT/TTS 的请求与响应值对象。
 *
 * <p>约束：
 * <ul>
 *   <li>只含平台语义：私有文件引用（{@code fileId}）与字节产物，不含厂商类型、临时 URL 与原始报文；</li>
 *   <li>构造即校验：MIME 形状、摘要一致性、尺寸/张数/时长/文本长度的平台硬上限，
 *       非法输入直接拒绝，不做截断或用默认值掩盖；</li>
 *   <li>厂商协议差异（multipart、base64、轮询任务）只允许出现在
 *       {@code com.basicframework.framework.ai.provider.springai} 适配层。</li>
 * </ul>
 *
 * <p>实时语音（FR-37）不在本包：需要会话协商、短期凭证与独立 ADR，V1.2 单独设计。
 */
package com.basicframework.framework.ai.core.model.media;
