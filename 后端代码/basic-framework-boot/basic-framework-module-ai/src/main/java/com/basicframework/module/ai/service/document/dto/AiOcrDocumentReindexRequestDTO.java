package com.basicframework.module.ai.service.document.dto;

import com.basicframework.module.ai.service.document.AiOcrPage;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * OCR 识别稿重索引请求（X02）：把逐页识别结果作为**该文档的新版本**入库。
 *
 * <p>不新建文档：sourceKey 复用原文档，指纹变化生成新版本，旧可用版本继续服务——
 * 这正是 K02 的"同 key 复用、指纹变则换版本"语义；失败时旧 active 版本不受影响（AT-024）。
 */
@Data
@Accessors(chain = true)
public class AiOcrDocumentReindexRequestDTO {

    /** 目标文档编号（必须已存在；sourceKey/知识库从该文档解析） */
    private Long documentId;

    /** 识别所依据的原始私有文件编号（写入来源位置用于追溯，要求为正数） */
    private Long sourceFileId;

    /** 逐页识别结果（页码从 1 开始、不得重复；全部页都没有正文时整笔拒绝） */
    private List<AiOcrPage> pages;
}
