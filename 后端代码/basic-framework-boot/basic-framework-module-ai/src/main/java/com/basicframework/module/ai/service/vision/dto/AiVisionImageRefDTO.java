package com.basicframework.module.ai.service.vision.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 图片输入引用（X02）：只标识平台私有文件与调用方声明，不携带字节、公网地址或厂商类型。
 *
 * <p>声明值一律**不被采信**：服务端按 A07 读取真实字节后交叉核对字节数、文件头与摘要，
 * 任何不一致都拒绝（见 {@code AiVisionImageGuard}）。字段名与前端契约
 * `packages/ai-contracts/src/multimodal.ts` 的 `imageInputSchema` 对齐（`mime` / `size`）。
 */
@Data
@Accessors(chain = true)
public class AiVisionImageRefDTO {

    /** 平台私有文件编号（正数；由受控上传签发） */
    private Long fileId;

    /** 声明的 MIME 类型；必须在平台白名单内（image/png、image/jpeg、image/webp） */
    private String mime;

    /** 声明的字节数 */
    private Long size;

    /** 声明的 SHA-256 摘要（可选；非空时必须与真实内容一致） */
    private String sha256;
}
