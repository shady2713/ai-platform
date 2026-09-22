package com.basicframework.module.ai.service.knowledge.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.module.ai.adapter.document.ParsedSegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/** K05 切片器：确定性点标识、块大小与重叠、位置继承（换 chunker 版本必须换代）。 */
class AiKnowledgeChunkerTest {

    private static final Long VERSION_ID = 81L;

    private static final String PARAGRAPH = "华东区域 8 月净额为 740.00 元，其中 C001 客户 290.00 元。";

    private final AiKnowledgeChunker chunker = new AiKnowledgeChunker(80, 20);

    private static ParsedSegment segment(int index, String location, String text) {
        return new ParsedSegment(index, location, text);
    }

    @Test
    void vectorIdsAreDeterministicAndIndependentOfContent() {
        String first = AiKnowledgeChunker.deterministicVectorId(VERSION_ID, 0);
        String second = AiKnowledgeChunker.deterministicVectorId(VERSION_ID, 0);
        String otherVersion = AiKnowledgeChunker.deterministicVectorId(82L, 0);
        String otherIndex = AiKnowledgeChunker.deterministicVectorId(VERSION_ID, 1);

        assertThat(first).isEqualTo(second).as("同版本同序号必须是同一个点（重跑幂等）");
        assertThat(first).isNotEqualTo(otherVersion).isNotEqualTo(otherIndex);
        assertThat(java.util.UUID.fromString(first)).as("向量服务要求点标识是 UUID").isNotNull();
    }

    @Test
    void splitsLongParagraphsIntoOverlappingChunksAndKeepsStartLocation() {
        String longText = PARAGRAPH.repeat(6);
        List<AiKnowledgeChunk> chunks =
                chunker.chunk(VERSION_ID, List.of(segment(0, "第 1 页", longText), segment(1, "第 2 页", PARAGRAPH)));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0).locationRef()).isEqualTo("第 1 页");
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(80 + 20 + 1);
            assertThat(chunk.contentHash()).hasSize(64);
            assertThat(chunk.vectorId()).isEqualTo(AiKnowledgeChunker.deterministicVectorId(VERSION_ID, chunk.index()));
        });
        assertThat(chunks.get(chunks.size() - 1).locationRef())
                .as("末块位置来自它起始的段落")
                .isNotNull();
    }

    @Test
    void reChunkingTheSameSegmentsProducesIdenticalChunkIds() {
        List<ParsedSegment> segments = List.of(segment(0, "段落 1", PARAGRAPH), segment(1, "段落 2", PARAGRAPH));

        List<AiKnowledgeChunk> first = chunker.chunk(VERSION_ID, segments);
        List<AiKnowledgeChunk> second = chunker.chunk(VERSION_ID, segments);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void emptyOrBlankSegmentsProduceNoChunks() {
        assertThat(chunker.chunk(VERSION_ID, List.of())).isEmpty();
        assertThat(chunker.chunk(VERSION_ID, null)).isEmpty();
        assertThat(chunker.chunk(VERSION_ID, List.of(segment(0, "段落 1", "   "))))
                .isEmpty();
    }

    @Test
    void rejectsInvalidChunkingParameters() {
        assertThatThrownBy(() -> new AiKnowledgeChunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiKnowledgeChunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiKnowledgeChunker(100, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
