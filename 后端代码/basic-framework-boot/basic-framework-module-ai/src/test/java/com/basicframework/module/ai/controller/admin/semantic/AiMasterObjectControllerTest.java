package com.basicframework.module.ai.controller.admin.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.common.pojo.PageResult;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterCatalogReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingEntryCreateReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingResolutionRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingResolveReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingReverseReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterMappingReverseRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectCatalogRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectPageReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectSaveReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterObjectStatusReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionDetailRespVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionDraftReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionPageReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionPublishReqVO;
import com.basicframework.module.ai.controller.admin.semantic.vo.AiMasterRevisionRespVO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.service.semantic.AiMasterMappingResolver;
import com.basicframework.module.ai.service.semantic.AiMasterObjectCatalogService;
import com.basicframework.module.ai.service.semantic.AiMasterObjectService;
import com.basicframework.module.ai.service.semantic.dto.AiMasterCatalogEntryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingEntrySaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolutionDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingResolveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingReverseResultDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogQueryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectSaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDetailDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDraftDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 主数据映射协议层契约（Y02）：VO 只做映射，判定时刻与版本号逐字段传递，
 * 冲突/过期/指纹由服务层事实决定（VO 不重算）。
 */
class AiMasterObjectControllerTest {

    private final AiMasterObjectService masterObjectService = mock(AiMasterObjectService.class);

    private final AiMasterMappingResolver mappingResolver = mock(AiMasterMappingResolver.class);

    private final AiMasterObjectCatalogService catalogService = mock(AiMasterObjectCatalogService.class);

    private final AiMasterObjectController controller =
            new AiMasterObjectController(masterObjectService, mappingResolver, catalogService);

    @Test
    void createUpdateAndStatusPassThroughProtocolFacts() {
        when(masterObjectService.createObject(any(AiMasterObjectSaveDTO.class))).thenReturn(42L);

        CommonResult<Long> created = controller.createObject(new AiMasterObjectSaveReqVO()
                .setObjectCode("md_customer")
                .setObjectName("企业客户")
                .setObjectType("CUSTOMER")
                .setDescription("说明"));

        assertThat(created.getData()).isEqualTo(42L);
        ArgumentCaptor<AiMasterObjectSaveDTO> captor = ArgumentCaptor.forClass(AiMasterObjectSaveDTO.class);
        verify(masterObjectService).createObject(captor.capture());
        assertThat(captor.getValue().getObjectCode()).isEqualTo("md_customer");
        assertThat(captor.getValue().getObjectType()).isEqualTo("CUSTOMER");

        controller.updateObject(new AiMasterObjectSaveReqVO()
                .setId(42L)
                .setObjectName("企业客户 v2")
                .setObjectType("CUSTOMER")
                .setVersion(3));
        ArgumentCaptor<AiMasterObjectSaveDTO> updateCaptor = ArgumentCaptor.forClass(AiMasterObjectSaveDTO.class);
        verify(masterObjectService).updateObject(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getId()).isEqualTo(42L);
        assertThat(updateCaptor.getValue().getVersion()).isEqualTo(3);

        controller.updateObjectStatus(
                new AiMasterObjectStatusReqVO().setId(42L).setVersion(4).setEnabled(false));
        verify(masterObjectService).updateObjectStatus(42L, 4, false);
    }

    @Test
    void objectQueriesMapRowsAndPagination() {
        when(masterObjectService.getObject(42L)).thenReturn(objectRow());
        when(masterObjectService.getObjectByCode("md_customer")).thenReturn(objectRow());
        when(masterObjectService.getObjectPage(
                        any(AiMasterObjectPageReqVO.class), eq("CUSTOMER"), eq("ACTIVE"), eq("md")))
                .thenReturn(new PageResult<>(List.of(objectRow()), 1L));

        AiMasterObjectRespVO detail = controller.getObject(42L).getData();
        assertThat(detail.getObjectCode()).isEqualTo("md_customer");
        assertThat(detail.getStatus()).isEqualTo("ACTIVE");
        assertThat(detail.getCurrentRevision()).isZero();
        assertThat(controller.getObjectByCode("md_customer").getData().getId()).isEqualTo(42L);

        PageResult<AiMasterObjectRespVO> page = controller
                .getObjectPage(new AiMasterObjectPageReqVO()
                        .setObjectType("CUSTOMER")
                        .setStatus("ACTIVE")
                        .setKeyword("md"))
                .getData();
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).hasSize(1);
    }

    @Test
    void revisionDraftEntryAndDeletePassThroughFacts() {
        when(masterObjectService.createRevision(any(AiMasterRevisionDraftDTO.class)))
                .thenReturn(1L);
        when(masterObjectService.addMappingEntry(any(AiMasterMappingEntrySaveDTO.class)))
                .thenReturn(77L);

        assertThat(controller
                        .createRevision(new AiMasterRevisionDraftReqVO()
                                .setMasterObjectId(42L)
                                .setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0)))
                        .getData())
                .isEqualTo(1L);

        assertThat(controller
                        .createMappingEntry(new AiMasterMappingEntryCreateReqVO()
                                .setMasterObjectId(42L)
                                .setRevisionNo(1L)
                                .setApplicationId(7L)
                                .setEntityType("customer")
                                .setSourceKey("C-1001")
                                .setSourceName("杭州云启科技有限公司")
                                .setMatchMethod("MANUAL")
                                .setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0)))
                        .getData())
                .isEqualTo(77L);
        ArgumentCaptor<AiMasterMappingEntrySaveDTO> captor = ArgumentCaptor.forClass(AiMasterMappingEntrySaveDTO.class);
        verify(masterObjectService).addMappingEntry(captor.capture());
        assertThat(captor.getValue().getSourceKey()).isEqualTo("C-1001");
        assertThat(captor.getValue().getMatchMethod()).isEqualTo("MANUAL");

        controller.deleteMappingEntry(77L, 0);
        verify(masterObjectService).removeMappingEntry(77L, 0);
    }

    @Test
    void publishAndRevisionQueriesExposeFrozenFingerprint() {
        AiMasterObjectRevisionDO published = revisionRow("PUBLISHED");
        when(masterObjectService.publishRevision(42L, 1L, 0)).thenReturn(published);
        when(masterObjectService.getRevision(42L, 1L)).thenReturn(published);
        when(masterObjectService.getRevisionPage(any(AiMasterRevisionPageReqVO.class), eq(42L), eq("PUBLISHED")))
                .thenReturn(new PageResult<>(List.of(published), 1L));
        when(masterObjectService.getRevisionDetail(42L, 1L))
                .thenReturn(new AiMasterRevisionDetailDTO()
                        .setRevision(revisionRow("DRAFT"))
                        .setEntries(List.of(mappingRow()))
                        .setEntryProblems(Map.of(77L, "CONFLICT"))
                        .setConflictKeys(List.of("42/7/customer"))
                        .setPublishable(false));

        AiMasterRevisionRespVO publishedVo = controller
                .publishRevision(new AiMasterRevisionPublishReqVO()
                        .setMasterObjectId(42L)
                        .setRevisionNo(1L)
                        .setVersion(0))
                .getData();
        assertThat(publishedVo.getMappingFingerprint()).isEqualTo("fingerprint");
        assertThat(publishedVo.getEntryCount()).isEqualTo(1);

        assertThat(controller.getRevision(42L, 1L).getData().getStatus()).isEqualTo("PUBLISHED");
        assertThat(controller
                        .getRevisionPage(new AiMasterRevisionPageReqVO()
                                .setMasterObjectId(42L)
                                .setStatus("PUBLISHED"))
                        .getData()
                        .getTotal())
                .isEqualTo(1L);

        AiMasterRevisionDetailRespVO detail =
                controller.getRevisionDetail(42L, 1L).getData();
        assertThat(detail.isPublishable()).isFalse();
        assertThat(detail.getConflictKeys()).containsExactly("42/7/customer");
        assertThat(detail.getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getSourceKey()).isEqualTo("C-1001");
            assertThat(entry.getProblem()).isEqualTo("CONFLICT");
        });
    }

    @Test
    void resolveCatalogAndReverseMapServiceFactsAsIs() {
        when(mappingResolver.resolveObjectKey(any(AiMasterMappingResolveDTO.class)))
                .thenReturn(new AiMasterMappingResolutionDTO()
                        .setMasterObjectId(42L)
                        .setObjectCode("md_customer")
                        .setObjectType("CUSTOMER")
                        .setRevisionNo(1L)
                        .setRevisionFingerprint("fingerprint")
                        .setAsOf(LocalDateTime.of(2026, 6, 1, 0, 0))
                        .setApplicationId(7L)
                        .setEntityType("customer")
                        .setSourceKey("C-1001")
                        .setMatchMethod("MANUAL"));
        when(mappingResolver.resolveSourceKey(any(AiMasterMappingReverseDTO.class)))
                .thenReturn(new AiMasterMappingReverseResultDTO()
                        .setMapped(false)
                        .setReason("NOT_REGISTERED")
                        .setApplicationId(7L)
                        .setEntityType("customer")
                        .setSourceKey("C-9999")
                        .setAsOf(LocalDateTime.of(2026, 6, 1, 0, 0)));
        when(catalogService.discover(any(AiMasterObjectCatalogQueryDTO.class)))
                .thenReturn(new AiMasterObjectCatalogDTO()
                        .setMasterObjectId(42L)
                        .setObjectCode("md_customer")
                        .setObjectType("CUSTOMER")
                        .setRevisionNo(1L)
                        .setRevisionFingerprint("fingerprint")
                        .setAsOf(LocalDateTime.of(2026, 6, 1, 0, 0))
                        .setDenied(true)
                        .setEntries(List.of(new AiMasterCatalogEntryDTO()
                                .setApplicationId(7L)
                                .setAppCode("crm")
                                .setEntityType("customer")
                                .setSourceKey("C-1001")
                                .setInForce(true)
                                .setUsable(true)
                                .setProblem("NONE")))
                        .setCatalogFingerprint("catalog-fingerprint")
                        .setModelCatalog("[]"));

        AiMasterMappingResolutionRespVO resolution = controller
                .resolveObjectKey(new AiMasterMappingResolveReqVO()
                        .setObjectCode("md_customer")
                        .setRevisionNo(1L)
                        .setApplicationId(7L)
                        .setEntityType("customer")
                        .setAsOf(LocalDateTime.of(2026, 6, 1, 0, 0)))
                .getData();
        assertThat(resolution.getRevisionNo()).isEqualTo(1L);
        assertThat(resolution.getRevisionFingerprint()).isEqualTo("fingerprint");
        assertThat(resolution.getSourceKey()).isEqualTo("C-1001");

        AiMasterMappingReverseRespVO reverse = controller
                .resolveSourceKey(new AiMasterMappingReverseReqVO()
                        .setApplicationId(7L)
                        .setEntityType("customer")
                        .setSourceKey("C-9999")
                        .setAsOf(LocalDateTime.of(2026, 6, 1, 0, 0)))
                .getData();
        assertThat(reverse.isMapped()).isFalse();
        assertThat(reverse.getReason()).isEqualTo("NOT_REGISTERED");

        AiMasterObjectCatalogRespVO catalog = controller
                .getCatalog(new AiMasterCatalogReqVO()
                        .setObjectCode("md_customer")
                        .setRevisionNo(1L)
                        .setApplicationId(7L)
                        .setSubjectType("USER")
                        .setExternalUserId("alice")
                        .setAsOf(LocalDateTime.of(2026, 6, 1, 0, 0)))
                .getData();
        assertThat(catalog.isDenied()).isTrue();
        assertThat(catalog.getCatalogFingerprint()).isEqualTo("catalog-fingerprint");
        assertThat(catalog.getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getAppCode()).isEqualTo("crm");
            assertThat(entry.isUsable()).isTrue();
        });
    }

    private static AiMasterObjectDO objectRow() {
        return new AiMasterObjectDO()
                .setId(42L)
                .setObjectCode("md_customer")
                .setObjectName("企业客户")
                .setObjectType("CUSTOMER")
                .setDescription("")
                .setStatus("ACTIVE")
                .setCurrentRevision(0L)
                .setVersion(3);
    }

    private static AiMasterObjectRevisionDO revisionRow(String status) {
        return new AiMasterObjectRevisionDO()
                .setId(11L)
                .setMasterObjectId(42L)
                .setRevisionNo(1L)
                .setStatus(status)
                .setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .setEntryCount(1)
                .setMappingFingerprint("fingerprint")
                .setCreatedBy(1001L)
                .setPublishedBy(1002L)
                .setVersion(1);
    }

    private static AiMasterObjectMappingDO mappingRow() {
        return new AiMasterObjectMappingDO()
                .setId(77L)
                .setMasterObjectId(42L)
                .setRevision(1L)
                .setApplicationId(7L)
                .setEntityType("customer")
                .setSourceKey("C-1001")
                .setSourceName("杭州云启科技有限公司")
                .setMatchMethod("MANUAL")
                .setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0))
                .setVersion(0);
    }
}
