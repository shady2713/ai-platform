package com.basicframework.module.ai.service.document;

import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexResultDTO;

/**
 * OCR 识别稿重索引（X02）：把识别稿作为该文档的新版本落到**既有知识入库流水线**上。
 *
 * <p>职责边界（也是本服务不"另起一套"的地方）：
 * <ul>
 *   <li>只做"生成派生文件 + 走既有入库"：版本、任务、切片、向量、active 切换全部沿用 K02–K05 的实现；</li>
 *   <li>识别稿没有正文时整笔拒绝，**不上传文件、不建版本**——因此旧可用版本不会被动到；</li>
 *   <li>入库失败时按 K03 语义补偿（解除派生文件引用），并保留旧 active 版本；</li>
 *   <li>本服务不调用模型：识别结果的产出来自 {@code AiVisionService} 的受控入口（能力准入 + 业务 ACL）。</li>
 * </ul>
 */
public interface AiOcrDocumentReindexService {

    /** 重索引：识别稿 → 新版本 → 既有入库任务（同 sourceKey、指纹变则换版本）。 */
    AiOcrDocumentReindexResultDTO reindex(AiOcrDocumentReindexRequestDTO request);
}
