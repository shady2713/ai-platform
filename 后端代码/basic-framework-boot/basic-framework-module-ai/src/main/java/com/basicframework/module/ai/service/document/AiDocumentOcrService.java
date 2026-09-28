package com.basicframework.module.ai.service.document;

import com.basicframework.module.ai.service.document.dto.AiDocumentOcrRequestDTO;
import com.basicframework.module.ai.service.document.dto.AiOcrDocumentReindexResultDTO;

/**
 * 扫描件 OCR 重索引（X02）：逐页受控图片 → OCR（能力准入 + 业务 ACL + 格式/像素/体积校验）
 * → 识别稿（页码/范围/置信度来源）→ 既有入库流水线的新版本。
 *
 * <p>安全语义：
 * <ul>
 *   <li><b>先授权后外发</b>：调用主体必须对目标知识库有 READ 授权（A03），否则在任何模型调用之前拒绝；</li>
 *   <li><b>分页失败不落半成品</b>：任何一页识别失败即整笔中断，不生成识别稿、不建版本，
 *       旧可用版本继续服务（AT-024）；</li>
 *   <li><b>不伪装识别质量</b>：结果携带置信度来源与"需人工复核"标记，识别稿正文里同样写明。</li>
 * </ul>
 */
public interface AiDocumentOcrService {

    /** 逐页识别并把识别稿重索引为该文档的新版本。 */
    AiOcrDocumentReindexResultDTO recognizeAndReindex(AiDocumentOcrRequestDTO request);
}
