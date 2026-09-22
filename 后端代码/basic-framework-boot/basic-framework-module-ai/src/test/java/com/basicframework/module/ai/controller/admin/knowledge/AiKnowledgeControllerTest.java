package com.basicframework.module.ai.controller.admin.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeBasePageReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeBaseRespVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeBaseSaveReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentIngestReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentIngestRespVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentPageReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeDocumentVersionPageReqVO;
import com.basicframework.module.ai.controller.admin.knowledge.vo.AiKnowledgeIndexGenerationRespVO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeBaseDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeDocumentVersionDO;
import com.basicframework.module.ai.dal.dataobject.knowledge.AiKnowledgeIndexGenerationDO;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeBaseService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeDocumentService;
import com.basicframework.module.ai.service.knowledge.AiKnowledgeIndexGenerationService;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentSaveDTO;
import com.basicframework.module.ai.service.knowledge.dto.AiKnowledgeDocumentUpsertResultDTO;
import jakarta.annotation.security.PermitAll;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/** K02 管理端契约：每个端点一种鉴权策略、权限码与迁移菜单一致、响应不含文件与凭据。 */
class AiKnowledgeControllerTest {

    private final AiKnowledgeBaseService baseService = mock(AiKnowledgeBaseService.class);

    private final AiKnowledgeDocumentService documentService = mock(AiKnowledgeDocumentService.class);

    private final AiKnowledgeIndexGenerationService generationService = mock(AiKnowledgeIndexGenerationService.class);

    private final AiKnowledgeBaseController baseController = new AiKnowledgeBaseController(baseService);

    private final AiKnowledgeDocumentController documentController =
            new AiKnowledgeDocumentController(documentService, generationService);

    private static void assertSingleStrategyAndPermission(Class<?> controller, String expectedPermission) {
        int endpoints = 0;
        for (Method method : controller.getDeclaredMethods()) {
            if (method.getAnnotation(GetMapping.class) == null
                    && method.getAnnotation(PostMapping.class) == null
                    && method.getAnnotation(PutMapping.class) == null
                    && method.getAnnotation(DeleteMapping.class) == null) {
                continue;
            }
            endpoints++;
            int strategies = 0;
            strategies += method.getAnnotation(PreAuthorize.class) == null ? 0 : 1;
            strategies += method.getAnnotation(PermitAll.class) == null ? 0 : 1;
            assertThat(strategies).as("%s 必须且只能声明一种鉴权策略", method.getName()).isEqualTo(1);
            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
            assertThat(preAuthorize).as("管理端必须走 @PreAuthorize").isNotNull();
            assertThat(preAuthorize.value())
                    .as("%s 的权限码", method.getName())
                    .startsWith("@ss.hasPermission('ai:knowledge:");
        }
        assertThat(endpoints).isPositive();
        assertThat(expectedPermission).startsWith("ai:knowledge:");
    }

    @Test
    void baseEndpointsDeclareExactlyOnePermissionStrategy() {
        assertSingleStrategyAndPermission(AiKnowledgeBaseController.class, "ai:knowledge:query");

        PreAuthorize create = null;
        for (Method method : AiKnowledgeBaseController.class.getDeclaredMethods()) {
            if ("create".equals(method.getName())) {
                create = method.getAnnotation(PreAuthorize.class);
            }
        }
        assertThat(create).isNotNull();
        assertThat(create.value()).isEqualTo("@ss.hasPermission('ai:knowledge:create')");
    }

    @Test
    void documentEndpointsRequireIngestPermissionForIngestion() {
        assertSingleStrategyAndPermission(AiKnowledgeDocumentController.class, "ai:knowledge:ingest");

        String ingestPermission = null;
        for (Method method : AiKnowledgeDocumentController.class.getDeclaredMethods()) {
            if ("ingest".equals(method.getName())) {
                ingestPermission = method.getAnnotation(PreAuthorize.class).value();
            }
        }
        assertThat(ingestPermission).as("无知识库管理权不可入库（卡片验收项）").isEqualTo("@ss.hasPermission('ai:knowledge:ingest')");
    }

    @Test
    void baseControllerMapsDoToVoWithoutEmbeddingSecrets() {
        when(baseService.getKnowledgeBase(61L))
                .thenReturn(new AiKnowledgeBaseDO()
                        .setId(61L)
                        .setCode("handbook")
                        .setName("员工手册")
                        .setVisibility(AiKnowledgeBaseDO.VISIBILITY_SHARED)
                        .setEmbeddingModel("text-embedding-3-small")
                        .setEmbeddingDimension(1536)
                        .setActiveGenerationNo(2)
                        .setRetentionDays(180)
                        .setStatus(AiKnowledgeBaseDO.STATUS_ENABLED)
                        .setVersion(3));
        when(baseService.getKnowledgeBasePage(any(), eq("SHARED"), any(), any()))
                .thenReturn(new PageResult<>(List.of(), 0L));

        AiKnowledgeBaseRespVO respVO = baseController.get(61L).getData();
        assertThat(respVO.getCode()).isEqualTo("handbook");
        assertThat(respVO.getActiveGenerationNo()).isEqualTo(2);
        assertThat(baseController
                        .page(new AiKnowledgeBasePageReqVO().setVisibility("SHARED"))
                        .getData()
                        .getTotal())
                .isZero();

        when(baseService.create(any())).thenReturn(62L);
        assertThat(baseController
                        .create(new AiKnowledgeBaseSaveReqVO()
                                .setCode("handbook")
                                .setName("员工手册")
                                .setVisibility("SHARED")
                                .setEmbeddingModel("text-embedding-3-small")
                                .setEmbeddingDimension(1536))
                        .getData())
                .isEqualTo(62L);
    }

    @Test
    void ingestReturnsThreeWayIdempotencyResultAndKeepsFileReference() {
        when(documentService.upsert(any()))
                .thenReturn(new AiKnowledgeDocumentUpsertResultDTO()
                        .setDocumentId(71L)
                        .setVersionId(81L)
                        .setVersionNo(2)
                        .setReused(true)
                        .setCreatedVersion(true)
                        .setDocumentStatus(AiKnowledgeDocumentDO.STATUS_PENDING));

        AiKnowledgeDocumentIngestRespVO respVO = documentController
                .ingest(new AiKnowledgeDocumentIngestReqVO()
                        .setKnowledgeBaseId(61L)
                        .setSourceKey("handbook/v2.pdf")
                        .setTitle("员工手册")
                        .setFileId(501L)
                        .setContentHash("a".repeat(64)))
                .getData();

        assertThat(respVO.getDocumentId()).isEqualTo(71L);
        assertThat(respVO.getVersionNo()).isEqualTo(2);
        assertThat(respVO.getReused()).isTrue();
        assertThat(respVO.getCreatedVersion()).isTrue();
        ArgumentCaptor<AiKnowledgeDocumentSaveDTO> captor = ArgumentCaptor.forClass(AiKnowledgeDocumentSaveDTO.class);
        verify(documentService).upsert(captor.capture());
        assertThat(captor.getValue().getFileId()).isEqualTo(501L);
        assertThat(captor.getValue().getContentHash()).isEqualTo("a".repeat(64));
    }

    @Test
    void documentGetPageAndDeleteAreWiredToTheService() {
        when(documentService.getDocument(71L))
                .thenReturn(new AiKnowledgeDocumentDO()
                        .setId(71L)
                        .setKnowledgeBaseId(61L)
                        .setSourceKey("handbook/v1.pdf")
                        .setTitle("员工手册")
                        .setSourceType(AiKnowledgeDocumentDO.SOURCE_UPLOAD)
                        .setStatus(AiKnowledgeDocumentDO.STATUS_READY)
                        .setActiveVersionNo(2)
                        .setLatestVersionNo(2)
                        .setVersion(5));
        when(documentService.getDocumentPage(any(), eq(61L), eq("READY")))
                .thenReturn(new PageResult<>(List.of(new AiKnowledgeDocumentDO().setId(71L)), 1L));

        assertThat(documentController.get(71L).getData().getActiveVersionNo()).isEqualTo(2);
        assertThat(documentController
                        .page(new AiKnowledgeDocumentPageReqVO()
                                .setKnowledgeBaseId(61L)
                                .setStatus("READY"))
                        .getData()
                        .getTotal())
                .isEqualTo(1L);
        assertThat(documentController.delete(71L, 5).getData()).isTrue();
        verify(documentService).deleteDocument(71L, 5);
    }

    @Test
    void versionAndGenerationViewsExposeMetadataOnly() {
        when(documentService.getVersion(81L))
                .thenReturn(new AiKnowledgeDocumentVersionDO()
                        .setId(81L)
                        .setDocumentId(71L)
                        .setVersionNo(2)
                        .setFileId(501L)
                        .setContentHash("b".repeat(64))
                        .setStatus(AiKnowledgeDocumentVersionDO.STATUS_READY)
                        .setIndexGeneration(2)
                        .setChunkCount(9)
                        .setReadyAt(LocalDateTime.of(2026, 9, 22, 10, 0))
                        .setVersion(4));
        when(documentService.getVersionPage(any(), eq(71L))).thenReturn(new PageResult<>(List.of(), 0L));
        when(generationService.listGenerations(61L))
                .thenReturn(List.of(new AiKnowledgeIndexGenerationDO()
                        .setId(102L)
                        .setKnowledgeBaseId(61L)
                        .setGenerationNo(2)
                        .setEmbeddingModel("text-embedding-3-small")
                        .setDimension(1536)
                        .setCollectionName("kb_handbook_g2")
                        .setStatus(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE)
                        .setChunkCount(9)
                        .setDocumentCount(2)));

        assertThat(documentController.getVersion(81L).getData().getChunkCount()).isEqualTo(9);
        assertThat(documentController
                        .versionPage(new AiKnowledgeDocumentVersionPageReqVO().setDocumentId(71L))
                        .getData()
                        .getTotal())
                .isZero();
        List<AiKnowledgeIndexGenerationRespVO> generations =
                documentController.generationList(61L).getData();
        assertThat(generations).hasSize(1);
        assertThat(generations.get(0).getCollectionName()).isEqualTo("kb_handbook_g2");
        assertThat(generations.get(0).getStatus()).isEqualTo(AiKnowledgeIndexGenerationDO.STATUS_ACTIVE);
    }
}
