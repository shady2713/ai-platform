package com.basicframework.module.ai.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.dal.dataobject.serviceconfig.AiServiceResourceDO;
import com.basicframework.module.ai.dal.mysql.serviceconfig.AiServiceResourceMapper;
import com.basicframework.module.ai.service.knowledge.rag.AiKnowledgeRagStep;
import com.basicframework.module.ai.service.knowledge.retrieval.AiKnowledgeRetrievalService;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeCitationDTO;
import com.basicframework.module.ai.service.knowledge.retrieval.dto.AiKnowledgeRetrievalResultDTO;
import java.util.List;
import org.junit.jupiter.api.Test;

/** K08 运行链路装配：知识库范围来自发布版本绑定、检索走 K06、无绑定即证据不足。 */
class AiRunKnowledgeContextServiceTest {

    private final AiServiceResourceMapper serviceResourceMapper = mock(AiServiceResourceMapper.class);

    private final AiKnowledgeRetrievalService retrievalService = mock(AiKnowledgeRetrievalService.class);

    private final AiRunKnowledgeContextService service =
            new AiRunKnowledgeContextService(serviceResourceMapper, retrievalService, new AiKnowledgeRagStep());

    private static AiServiceResourceDO binding(String resourceType, String resourceKey) {
        return new AiServiceResourceDO()
                .setResourceType(resourceType)
                .setResourceKey(resourceKey)
                .setActions("READ")
                .setStatus(AiServiceResourceDO.STATUS_ACTIVE);
    }

    private static AiKnowledgeCitationDTO citation(String text) {
        return new AiKnowledgeCitationDTO()
                .setCitationId("61:81:0")
                .setKnowledgeBaseId(61L)
                .setDocumentId(71L)
                .setTitle("员工手册")
                .setVersionNo(1)
                .setChunkIndex(0)
                .setLocationRef("段落 1")
                .setSnippet(text);
    }

    @Test
    void boundKnowledgeBasesOnlyIncludeKnowledgeResourceTypeAndDeduplicate() {
        when(serviceResourceMapper.selectActiveReleaseBindings(21L))
                .thenReturn(List.of(
                        binding("KNOWLEDGE_BASE", "handbook"),
                        binding("KNOWLEDGE_BASE", "handbook"),
                        binding("TOOL", "orders-tool"),
                        binding("KNOWLEDGE_BASE", "policy")));

        assertThat(service.boundKnowledgeBases(21L)).containsExactly("handbook", "policy");
        assertThat(service.boundKnowledgeBases(null)).isEmpty();
    }

    @Test
    void assembleRetrievesOnlyWhenTheReleaseBindsKnowledgeBases() {
        when(serviceResourceMapper.selectActiveReleaseBindings(21L))
                .thenReturn(List.of(binding("KNOWLEDGE_BASE", "handbook")));
        when(retrievalService.search(eq("华东净额是多少"), any()))
                .thenReturn(new AiKnowledgeRetrievalResultDTO().setCitations(List.of(citation("净额 740.00 元。"))));

        var context = service.assemble(21L, "华东净额是多少", 5);

        assertThat(context.isInsufficientEvidence()).isFalse();
        assertThat(context.getSnippets()).hasSize(1);
        assertThat(context.isUntrusted()).isTrue();
        assertThat(service.validateAnswer("净额 740.00 元 [1]", context).getValidCitations())
                .containsExactly(1);

        // 没有绑定知识库：不检索，直接证据不足
        when(serviceResourceMapper.selectActiveReleaseBindings(22L))
                .thenReturn(List.of(binding("TOOL", "orders-tool")));
        var empty = service.assemble(22L, "华东净额是多少", 5);
        assertThat(empty.isInsufficientEvidence()).isTrue();
        assertThat(empty.getSnippets()).isEmpty();
        // 只有绑定知识库的那次装配触发了检索
        verify(retrievalService, org.mockito.Mockito.times(1)).search(any(), anyInt());
    }
}
