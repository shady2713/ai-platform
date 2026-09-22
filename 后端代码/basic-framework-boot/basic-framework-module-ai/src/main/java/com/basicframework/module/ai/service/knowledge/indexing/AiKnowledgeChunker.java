package com.basicframework.module.ai.service.knowledge.indexing;

import com.basicframework.module.ai.adapter.document.ParsedSegment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 切片器（K05）：固定 chunker 版本 + 稳定 chunkId + 位置继承。
 *
 * <p>为什么 chunkId 必须稳定：索引写入是**幂等**的——同一版本重跑必须覆盖同一批向量点，
 * 否则"中途失败重试"会在索引里留下两批内容（旧批永远检索得到）。因此点标识由
 * {@code (documentVersionId, chunkerVersion, chunkIndex)} 确定性派生（K01 要求点 ID 是 UUID），
 * 内容哈希只用于判断"内容是否变化"，不参与点标识。
 *
 * <p>切分规则：按段落拼接到不超过 {@code maxCharacters} 的块，块间保留 {@code overlapCharacters} 重叠，
 * 位置取该块**起始段落**的位置（引用回到原文的起点）。换 chunker 版本 = 换索引代（AT-029）。
 */
public class AiKnowledgeChunker {

    /** 当前 chunker 版本（变更切分规则必须改它，并随之换索引代）。 */
    public static final String CHUNKER_VERSION = "v1";

    /** 默认单块字符上限。 */
    public static final int DEFAULT_MAX_CHARACTERS = 800;

    /** 默认块间重叠字符数。 */
    public static final int DEFAULT_OVERLAP_CHARACTERS = 120;

    private final int maxCharacters;

    private final int overlapCharacters;

    public AiKnowledgeChunker() {
        this(DEFAULT_MAX_CHARACTERS, DEFAULT_OVERLAP_CHARACTERS);
    }

    public AiKnowledgeChunker(int maxCharacters, int overlapCharacters) {
        if (maxCharacters <= 0 || overlapCharacters < 0 || overlapCharacters >= maxCharacters) {
            throw new IllegalArgumentException("切分参数不合法");
        }
        this.maxCharacters = maxCharacters;
        this.overlapCharacters = overlapCharacters;
    }

    /** 切片（空段落列表返回空；正文为空不产生切片）。 */
    public List<AiKnowledgeChunk> chunk(Long documentVersionId, List<ParsedSegment> segments) {
        List<AiKnowledgeChunk> chunks = new ArrayList<>();
        if (segments == null || segments.isEmpty()) {
            return chunks;
        }
        StringBuilder buffer = new StringBuilder();
        String startLocation = segments.get(0).locationRef();
        for (ParsedSegment segment : segments) {
            String text = segment.text() == null ? "" : segment.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (buffer.isEmpty()) {
                startLocation = segment.locationRef();
            }
            // 单个段落就超过上限：按窗口切开（带重叠），避免产出超长块
            while (text.length() > maxCharacters) {
                if (!buffer.isEmpty()) {
                    chunks.add(build(documentVersionId, chunks.size(), startLocation, buffer.toString()));
                    buffer.setLength(0);
                }
                chunks.add(build(documentVersionId, chunks.size(), startLocation, text.substring(0, maxCharacters)));
                text = text.substring(maxCharacters - overlapCharacters);
            }
            if (text.isEmpty()) {
                continue;
            }
            if (buffer.length() + text.length() + 1 > maxCharacters && !buffer.isEmpty()) {
                chunks.add(build(documentVersionId, chunks.size(), startLocation, buffer.toString()));
                String tail = tail(buffer.toString(), overlapCharacters);
                buffer.setLength(0);
                if (!tail.isEmpty()) {
                    buffer.append(tail).append('\n');
                }
                startLocation = segment.locationRef();
            }
            if (!buffer.isEmpty()) {
                buffer.append('\n');
            }
            buffer.append(text);
        }
        if (!buffer.isEmpty()) {
            chunks.add(build(documentVersionId, chunks.size(), startLocation, buffer.toString()));
        }
        return chunks;
    }

    private AiKnowledgeChunk build(Long documentVersionId, int index, String locationRef, String text) {
        String normalized = text.trim();
        return new AiKnowledgeChunk(
                index,
                locationRef,
                normalized,
                sha256(normalized),
                deterministicVectorId(documentVersionId, index),
                normalized.length());
    }

    /** 确定性向量点标识：同一 (版本, chunker 版本, 序号) 永远同一个点。 */
    public static String deterministicVectorId(Long documentVersionId, int chunkIndex) {
        String seed = documentVersionId + ":" + CHUNKER_VERSION + ":" + chunkIndex;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static String tail(String text, int length) {
        if (length <= 0 || text.length() <= length) {
            return length <= 0 ? "" : text;
        }
        return text.substring(text.length() - length);
    }
}
