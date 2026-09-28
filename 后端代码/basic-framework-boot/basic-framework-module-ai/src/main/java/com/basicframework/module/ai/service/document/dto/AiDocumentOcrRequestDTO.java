package com.basicframework.module.ai.service.document.dto;

import com.basicframework.module.ai.service.vision.dto.AiVisionImageRefDTO;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 扫描件 OCR 重索引请求（X02）：文档的逐页图片（平台私有文件）→ 逐页 OCR → 新版本。
 *
 * <p>为什么逐页图片由调用方给出：X01 冻结的媒体端口以**平台私有文件**为唯一输入形态
 * （`MediaFileRef` = 文件编号 + MIME + 字节数 + 摘要），平台不为扫描件另造一套"直接传字节"的旁路；
 * 调用方（管理端栅格化流程或已有扫描图片）先把每页图片上传成私有文件，再交本接口按页识别。
 */
@Data
@Accessors(chain = true)
public class AiDocumentOcrRequestDTO {

    /** 目标文档编号（重索引到该文档的新版本） */
    private Long documentId;

    /** 模型端点编号（调用方显式指定，不做候选遍历） */
    private Long endpointId;

    /** 逐页图片（按页序给出；单次上限见 {@code AiVisionLimits.MAX_DOCUMENT_PAGES}） */
    private List<AiVisionImageRefDTO> pageImages;

    /** 语言提示（如 zh-CN）；为空表示由端点自行识别 */
    private String languageHint;

    /** 单页调用超时（毫秒）；为空表示使用端点默认值 */
    private Long timeoutMillis;
}
