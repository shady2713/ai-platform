package com.basicframework.module.ai.adapter.document;

/**
 * 受限文档解析器（K04）：把私有文件解析成带位置的正文段。
 *
 * <p>约束（每一条都由实现保证，并有对应用例）：
 * <ol>
 *   <li><b>白名单格式</b>：只解析 TXT/Markdown/PDF/DOCX，其余一律拒绝（不"尽力而为"）；</li>
 *   <li><b>有界</b>：字符总量、段落数、单次解析耗时都有上限，触顶即失败（不返回被截断的正文当完整结果）；</li>
 *   <li><b>不抓取远程资源</b>：不跟随文件内的外部引用、不解析内嵌文档、不做 OCR；</li>
 *   <li><b>扫描件不造假</b>：没有文本层的 PDF 返回"需要 OCR"，不生成占位正文；</li>
 *   <li><b>错误不泄内容</b>：失败只给稳定原因码与异常类型。</li>
 * </ol>
 */
public interface DocumentParser {

    /** 解析（内容与文件名都来自服务端已校验的文件；文件名只用于格式识别）。 */
    ParsedDocument parse(byte[] content, String fileName);
}
