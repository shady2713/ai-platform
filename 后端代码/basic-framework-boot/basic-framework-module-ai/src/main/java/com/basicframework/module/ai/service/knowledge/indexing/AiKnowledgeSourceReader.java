package com.basicframework.module.ai.service.knowledge.indexing;

/**
 * 原文读取端口（K05）：索引流水线通过它拿到文档版本的私有文件内容。
 *
 * <p>为什么是端口而不是直接依赖 infra：读取必须走业务 ACL（A07 的 Provider + A03 授权），
 * 端口让"谁在什么授权下读原文"成为可替换、可测试的策略点；实现见
 * `adapter/knowledge/FileApiKnowledgeSourceReader`。
 */
public interface AiKnowledgeSourceReader {

    /** 读取文件内容与文件名（文件名只用于格式识别；失败抛稳定原因码异常，不含内容）。 */
    KnowledgeSource read(Long fileId);

    /** 原文：内容 + 文件名。 */
    record KnowledgeSource(byte[] content, String fileName) {}
}
