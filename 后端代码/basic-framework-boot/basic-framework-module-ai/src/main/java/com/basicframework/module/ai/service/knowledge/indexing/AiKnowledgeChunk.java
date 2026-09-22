package com.basicframework.module.ai.service.knowledge.indexing;

/** 切片（K05）：序号 + 位置 + 正文 + 哈希 + 确定性向量点标识。 */
public record AiKnowledgeChunk(
        int index, String locationRef, String text, String contentHash, String vectorId, int characterCount) {}
