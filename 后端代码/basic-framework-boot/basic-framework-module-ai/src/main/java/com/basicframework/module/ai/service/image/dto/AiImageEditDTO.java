package com.basicframework.module.ai.service.image.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 图片编辑请求（服务层 DTO，X03）：底图用**私有文件引用 + 声明级元数据**，不传字节、不传地址。
 *
 * <p>声明元数据会在受理前与真实内容核对（A07 读取 + 图片核验）：不一致、无权或已解除引用的一律拒绝。
 */
@Data
@Accessors(chain = true)
public class AiImageEditDTO {

    /** 幂等键（调用方提供） */
    private String requestKey;

    /** 模型端点编号 */
    private Long endpointId;

    /** 编辑指令 */
    private String instruction;

    /** 底图平台私有文件编号 */
    private Long sourceFileId;

    /** 底图声明 MIME（白名单内取值） */
    private String sourceMime;

    /** 底图声明字节数 */
    private Long sourceSizeBytes;

    /** 底图声明摘要（可为空；非空必须与真实内容一致） */
    private String sourceSha256;

    /** 目标尺寸（宽x高；为空表示沿用底图尺寸） */
    private String size;

    /** 输出格式（为空按 png） */
    private String outputFormat;
}
