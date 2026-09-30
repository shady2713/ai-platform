package com.basicframework.module.ai.service.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.framework.security.core.LoginUser;
import com.basicframework.module.ai.dal.dataobject.application.AiApplicationDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMappingMapper;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectRevisionMapper;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiApplicationService;
import com.basicframework.module.ai.service.semantic.dto.AiMasterMappingEntrySaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectSaveDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDetailDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterRevisionDraftDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Y02 映射管理面：登记、草稿编辑、发布（独立审核 + 冲突阻断）与乐观锁。
 *
 * <p>负向用例是主体：发布人不能是草稿创建人、有冲突不许发布、已发布版本不可编辑、
 * 超预算不静默截断、标识不可修改。
 */
class AiMasterObjectServiceImplTest {

    private static final long OPERATOR = 1001L;

    private static final long OTHER_OPERATOR = 1002L;

    private static final long OBJECT_ID = 5L;

    private static final long APPLICATION_ID = 7L;

    private static final LocalDateTime JAN = LocalDateTime.of(2026, 1, 1, 0, 0);

    private AiMasterObjectMapper objectMapper;

    private AiMasterObjectRevisionMapper revisionMapper;

    private AiMasterObjectMappingMapper mappingMapper;

    private AiApplicationService applicationService;

    private AiMasterObjectServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = mock(AiMasterObjectMapper.class);
        revisionMapper = mock(AiMasterObjectRevisionMapper.class);
        mappingMapper = mock(AiMasterObjectMappingMapper.class);
        applicationService = mock(AiApplicationService.class);
        service = new AiMasterObjectServiceImpl(objectMapper, revisionMapper, mappingMapper, applicationService);
        loginAs(OPERATOR);
        when(applicationService.getApplication(APPLICATION_ID))
                .thenReturn(new AiApplicationDO()
                        .setId(APPLICATION_ID)
                        .setAppCode("it-crm")
                        .setEnabled(true));
        when(objectMapper.insert(any(AiMasterObjectDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiMasterObjectDO.class).setId(OBJECT_ID);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createObjectRegistersActiveObjectWithoutRevision() {
        when(objectMapper.selectByCode("md_customer")).thenReturn(null);

        Long id = service.createObject(new AiMasterObjectSaveDTO()
                .setObjectCode("md_customer")
                .setObjectName("企业客户")
                .setObjectType("customer")
                .setDescription("  CRM/ERP 客户统一对象  "));

        assertThat(id).isEqualTo(OBJECT_ID);
        ArgumentCaptor<AiMasterObjectDO> captor = ArgumentCaptor.forClass(AiMasterObjectDO.class);
        verify(objectMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiMasterObjectDO.STATUS_ACTIVE);
        assertThat(captor.getValue().getObjectType()).isEqualTo("CUSTOMER");
        assertThat(captor.getValue().getDescription()).isEqualTo("CRM/ERP 客户统一对象");
        assertThat(captor.getValue().getCurrentRevision()).isZero();
    }

    @Test
    void createObjectRejectsDuplicateCodeUnknownTypeAndAnonymousCaller() {
        when(objectMapper.selectByCode("md_customer")).thenReturn(object());
        assertCode(
                () -> service.createObject(new AiMasterObjectSaveDTO()
                        .setObjectCode("md_customer")
                        .setObjectName("企业客户")
                        .setObjectType("CUSTOMER")),
                AiErrorCodeConstants.AI_MASTER_OBJECT_CODE_DUPLICATE);
        assertCode(
                () -> service.createObject(new AiMasterObjectSaveDTO()
                        .setObjectCode("md_x")
                        .setObjectName("企业客户")
                        .setObjectType("UNKNOWN")),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(
                () -> service.createObject(new AiMasterObjectSaveDTO()
                        .setObjectCode("bad code")
                        .setObjectName("企业客户")
                        .setObjectType("CUSTOMER")),
                AiErrorCodeConstants.AI_REQUEST_INVALID);

        SecurityContextHolder.clearContext();
        assertCode(
                () -> service.createObject(new AiMasterObjectSaveDTO()
                        .setObjectCode("md_y")
                        .setObjectName("企业客户")
                        .setObjectType("CUSTOMER")),
                AiErrorCodeConstants.AI_ACCESS_DENIED);
    }

    @Test
    void updateObjectRefusesCodeChangeAndDetectsLostUpdate() {
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object());
        when(objectMapper.updateWithVersion(any(AiMasterObjectDO.class), eq(3))).thenReturn(1);

        service.updateObject(new AiMasterObjectSaveDTO()
                .setId(OBJECT_ID)
                .setObjectCode("md_customer")
                .setObjectName("企业客户 v2")
                .setObjectType("SUPPLIER")
                .setDescription("")
                .setVersion(3));
        ArgumentCaptor<AiMasterObjectDO> captor = ArgumentCaptor.forClass(AiMasterObjectDO.class);
        verify(objectMapper).updateWithVersion(captor.capture(), eq(3));
        assertThat(captor.getValue().getVersion()).isEqualTo(4);
        assertThat(captor.getValue().getObjectType()).isEqualTo("SUPPLIER");

        // 标识不可修改：请求里给出不同标识直接拒绝
        assertCode(
                () -> service.updateObject(new AiMasterObjectSaveDTO()
                        .setId(OBJECT_ID)
                        .setObjectCode("md_other")
                        .setObjectName("企业客户 v2")
                        .setObjectType("CUSTOMER")
                        .setVersion(3)),
                AiErrorCodeConstants.AI_REQUEST_INVALID);

        when(objectMapper.updateWithVersion(any(AiMasterObjectDO.class), eq(9))).thenReturn(0);
        assertCode(
                () -> service.updateObject(new AiMasterObjectSaveDTO()
                        .setId(OBJECT_ID)
                        .setObjectName("企业客户 v3")
                        .setObjectType("CUSTOMER")
                        .setVersion(9)),
                AiErrorCodeConstants.AI_STATE_CONFLICT);
    }

    @Test
    void updateObjectStatusIsIdempotentAndUsesCas() {
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object());

        // 目标状态已达成：不再消耗乐观锁版本
        service.updateObjectStatus(OBJECT_ID, 3, true);
        verify(objectMapper, never()).updateWithVersion(any(AiMasterObjectDO.class), anyInt());

        when(objectMapper.updateWithVersion(any(AiMasterObjectDO.class), eq(3))).thenReturn(1);
        service.updateObjectStatus(OBJECT_ID, 3, false);
        ArgumentCaptor<AiMasterObjectDO> captor = ArgumentCaptor.forClass(AiMasterObjectDO.class);
        verify(objectMapper).updateWithVersion(captor.capture(), eq(3));
        assertThat(captor.getValue().getStatus()).isEqualTo(AiMasterObjectDO.STATUS_DISABLED);

        when(objectMapper.updateWithVersion(any(AiMasterObjectDO.class), eq(3))).thenReturn(0);
        assertCode(() -> service.updateObjectStatus(OBJECT_ID, 3, false), AiErrorCodeConstants.AI_STATE_CONFLICT);
    }

    @Test
    void queriesRejectUnknownFiltersAndMissingObjects() {
        when(objectMapper.selectById(99L)).thenReturn(null);
        when(objectMapper.selectByCode("missing")).thenReturn(null);

        assertCode(() -> service.getObject(99L), AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS);
        assertCode(() -> service.getObjectByCode("missing"), AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS);
        assertCode(
                () -> service.getObjectPage(
                        new com.basicframework.framework.common.pojo.PageParam(), "CUSTOMER", "X", null),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(() -> service.getObjectPage(null, null, null, null), AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(
                () -> service.getRevisionPage(new com.basicframework.framework.common.pojo.PageParam(), OBJECT_ID, "X"),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(() -> service.getObject(null), AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS);
    }

    @Test
    void createRevisionAllowsOnlyOneOpenDraftAndNumbersVersions() {
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object());
        when(revisionMapper.selectLatest(OBJECT_ID)).thenReturn(null);
        when(revisionMapper.insert(any(AiMasterObjectRevisionDO.class))).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, AiMasterObjectRevisionDO.class).getRevisionNo())
                    .isEqualTo(1L);
            return 1;
        });

        assertThat(service.createRevision(draft())).isEqualTo(1L);
        ArgumentCaptor<AiMasterObjectRevisionDO> captor = ArgumentCaptor.forClass(AiMasterObjectRevisionDO.class);
        verify(revisionMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AiMasterObjectRevisionDO.STATUS_DRAFT);
        assertThat(captor.getValue().getCreatedBy()).isEqualTo(OPERATOR);

        // 已有一个未发布草稿：拒绝再建（"条目加到哪个草稿"必须有确定答案）
        when(revisionMapper.selectLatest(OBJECT_ID)).thenReturn(publishedRevision(1L));
        when(revisionMapper.selectLatest(OBJECT_ID)).thenReturn(draftRevision(2L));
        assertCode(() -> service.createRevision(draft()), AiErrorCodeConstants.AI_STATE_CONFLICT);

        // 上一个已发布：版本号继续递增
        when(revisionMapper.selectLatest(OBJECT_ID)).thenReturn(publishedRevision(2L));
        when(revisionMapper.insert(any(AiMasterObjectRevisionDO.class))).thenAnswer(invocation -> {
            assertThat(invocation.getArgument(0, AiMasterObjectRevisionDO.class).getRevisionNo())
                    .isEqualTo(3L);
            return 1;
        });
        assertThat(service.createRevision(draft())).isEqualTo(3L);

        assertCode(
                () -> service.createRevision(new AiMasterRevisionDraftDTO().setMasterObjectId(OBJECT_ID)),
                AiErrorCodeConstants.AI_REQUEST_INVALID);
    }

    @Test
    void addMappingEntryRequiresDraftRevisionAndUniqueSourceKey() {
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object());
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(draftRevision(1L));
        when(mappingMapper.countByRevision(OBJECT_ID, 1L)).thenReturn(0L);
        when(mappingMapper.selectEntry(eq(OBJECT_ID), eq(1L), eq(APPLICATION_ID), eq("customer"), eq("C-1001")))
                .thenReturn(null);
        when(mappingMapper.insert(any(AiMasterObjectMappingDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AiMasterObjectMappingDO.class).setId(77L);
            return 1;
        });

        Long entryId = service.addMappingEntry(entryDTO("C-1001"));

        assertThat(entryId).isEqualTo(77L);
        ArgumentCaptor<AiMasterObjectMappingDO> captor = ArgumentCaptor.forClass(AiMasterObjectMappingDO.class);
        verify(mappingMapper).insert(captor.capture());
        assertThat(captor.getValue().getRevision()).isEqualTo(1L);
        assertThat(captor.getValue().getMatchMethod()).isEqualTo("MANUAL");
        assertThat(captor.getValue().getVersion()).isZero();

        // 重复登记：同一版本的同一（系统, 实体类型, 源键）只能一次
        when(mappingMapper.selectEntry(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenReturn(new AiMasterObjectMappingDO().setId(1L));
        assertCode(
                () -> service.addMappingEntry(entryDTO("C-1001")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_DUPLICATE);

        // 预算：超上限拒绝，不静默截断
        when(mappingMapper.selectEntry(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenReturn(null);
        when(mappingMapper.countByRevision(OBJECT_ID, 1L))
                .thenReturn((long) AiMasterObjectServiceImpl.MAX_ENTRIES_PER_REVISION);
        assertCode(
                () -> service.addMappingEntry(entryDTO("C-1002")),
                AiErrorCodeConstants.AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED);

        // 已发布版本不可再登记
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(publishedRevision(1L));
        assertCode(
                () -> service.addMappingEntry(entryDTO("C-1003")),
                AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT);

        // 来源系统必须存在且启用
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(draftRevision(1L));
        when(applicationService.getApplication(APPLICATION_ID))
                .thenReturn(new AiApplicationDO().setId(APPLICATION_ID).setEnabled(false));
        assertCode(
                () -> service.addMappingEntry(entryDTO("C-1004")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID);
        when(applicationService.getApplication(APPLICATION_ID))
                .thenThrow(new ServiceException(AiErrorCodeConstants.AI_APPLICATION_NOT_FOUND));
        assertCode(
                () -> service.addMappingEntry(entryDTO("C-1005")),
                AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID);
    }

    @Test
    void removeMappingEntryOnlyOnDraftAndWithCas() {
        when(mappingMapper.selectById(77L)).thenReturn(entryRow(1L, "C-1001", JAN, null));
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(draftRevision(1L));
        when(mappingMapper.deleteEntry(77L, 0)).thenReturn(1);

        service.removeMappingEntry(77L, 0);
        verify(mappingMapper).deleteEntry(77L, 0);

        assertCode(
                () -> service.removeMappingEntry(null, 0), AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_EXISTS);
        when(mappingMapper.selectById(88L)).thenReturn(null);
        assertCode(() -> service.removeMappingEntry(88L, 0), AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_NOT_EXISTS);

        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(publishedRevision(1L));
        assertCode(
                () -> service.removeMappingEntry(77L, 0),
                AiErrorCodeConstants.AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT);

        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(draftRevision(1L));
        when(mappingMapper.deleteEntry(77L, 5)).thenReturn(0);
        assertCode(() -> service.removeMappingEntry(77L, 5), AiErrorCodeConstants.AI_STATE_CONFLICT);
    }

    @Test
    void publishRequiresIndependentReviewerAndBlocksConflicts() {
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object());
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(draftRevision(1L));
        when(mappingMapper.selectByRevision(OBJECT_ID, 1L)).thenReturn(List.of(entryRow(1L, "C-1001", JAN, null)));

        // 草稿创建人不能自己发布（独立审核）
        assertCode(
                () -> service.publishRevision(OBJECT_ID, 1L, 0),
                AiErrorCodeConstants.AI_MASTER_OBJECT_PUBLISHER_CONFLICT);

        loginAs(OTHER_OPERATOR);

        // 空版本没有可核验内容
        when(mappingMapper.selectByRevision(OBJECT_ID, 1L)).thenReturn(List.of());
        assertCode(
                () -> service.publishRevision(OBJECT_ID, 1L, 0), AiErrorCodeConstants.AI_MASTER_MAPPING_ENTRY_INVALID);
        verify(revisionMapper, never()).updateWithVersion(any(AiMasterObjectRevisionDO.class), anyInt());

        // 一对多冲突：同对象同系统同实体类型两条重叠
        when(mappingMapper.selectByRevision(OBJECT_ID, 1L))
                .thenReturn(List.of(entryRow(1L, "C-1001", JAN, null), entryRow(2L, "C-1002", JAN, null)));
        assertCode(() -> service.publishRevision(OBJECT_ID, 1L, 0), AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);

        // 多对一冲突：同一源键在其它对象的当前版本里重叠
        when(mappingMapper.selectByRevision(OBJECT_ID, 1L)).thenReturn(List.of(entryRow(1L, "C-1001", JAN, null)));
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of(entryRow(OBJECT_ID + 94L, 9L, "C-1001", APPLICATION_ID, JAN, null)));
        assertCode(() -> service.publishRevision(OBJECT_ID, 1L, 0), AiErrorCodeConstants.AI_MASTER_MAPPING_CONFLICT);

        // 非草稿不能发布
        when(mappingMapper.selectCurrentBySourceKey(APPLICATION_ID, "customer", "C-1001"))
                .thenReturn(List.of());
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(publishedRevision(1L));
        assertCode(() -> service.publishRevision(OBJECT_ID, 1L, 0), AiErrorCodeConstants.AI_STATE_CONFLICT);
    }

    @Test
    void publishFreezesFingerprintAndAdvancesCurrentRevision() {
        loginAs(OTHER_OPERATOR);
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object());
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(draftRevision(1L));
        List<AiMasterObjectMappingDO> entries =
                List.of(entryRow(1L, "C-1001", JAN, null), entryRow(2L, "E-9001", JAN, null, 8L));
        when(mappingMapper.selectByRevision(OBJECT_ID, 1L)).thenReturn(entries);
        when(mappingMapper.selectCurrentBySourceKey(anyLong(), any(), any())).thenReturn(List.of());
        when(revisionMapper.updateWithVersion(any(AiMasterObjectRevisionDO.class), eq(0)))
                .thenReturn(1);
        when(objectMapper.updateWithVersion(any(AiMasterObjectDO.class), eq(3))).thenReturn(1);

        AiMasterObjectRevisionDO published = service.publishRevision(OBJECT_ID, 1L, 0);

        ArgumentCaptor<AiMasterObjectRevisionDO> revisionCaptor =
                ArgumentCaptor.forClass(AiMasterObjectRevisionDO.class);
        verify(revisionMapper).updateWithVersion(revisionCaptor.capture(), eq(0));
        String expectedFingerprint = AiMasterMappingFacts.fingerprint(
                entries.stream().map(AiMasterObjectServiceImpl::toLine).toList());
        assertThat(revisionCaptor.getValue().getStatus()).isEqualTo(AiMasterObjectRevisionDO.STATUS_PUBLISHED);
        assertThat(revisionCaptor.getValue().getEntryCount()).isEqualTo(2);
        assertThat(revisionCaptor.getValue().getMappingFingerprint()).isEqualTo(expectedFingerprint);
        assertThat(revisionCaptor.getValue().getPublishedBy()).isEqualTo(OTHER_OPERATOR);
        assertThat(revisionCaptor.getValue().getVersion()).isEqualTo(1);

        ArgumentCaptor<AiMasterObjectDO> objectCaptor = ArgumentCaptor.forClass(AiMasterObjectDO.class);
        verify(objectMapper).updateWithVersion(objectCaptor.capture(), eq(3));
        assertThat(objectCaptor.getValue().getCurrentRevision()).isEqualTo(1L);
        assertThat(objectCaptor.getValue().getVersion()).isEqualTo(4);
        assertThat(published).isNotNull();
    }

    @Test
    void publishFailsClosedWhenCasLoses() {
        loginAs(OTHER_OPERATOR);
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object());
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(draftRevision(1L));
        when(mappingMapper.selectByRevision(OBJECT_ID, 1L)).thenReturn(List.of(entryRow(1L, "C-1001", JAN, null)));
        when(mappingMapper.selectCurrentBySourceKey(anyLong(), any(), any())).thenReturn(List.of());
        when(revisionMapper.updateWithVersion(any(AiMasterObjectRevisionDO.class), eq(0)))
                .thenReturn(0);
        assertCode(() -> service.publishRevision(OBJECT_ID, 1L, 0), AiErrorCodeConstants.AI_STATE_CONFLICT);

        // 版本 CAS 成功但对象推进失败：整体回滚（抛冲突，不留下"版本已发布但对象没指向它"）
        when(revisionMapper.updateWithVersion(any(AiMasterObjectRevisionDO.class), eq(0)))
                .thenReturn(1);
        when(objectMapper.updateWithVersion(any(AiMasterObjectDO.class), eq(3))).thenReturn(0);
        assertCode(() -> service.publishRevision(OBJECT_ID, 1L, 0), AiErrorCodeConstants.AI_STATE_CONFLICT);
    }

    @Test
    void revisionDetailFlagsConflictsExpiryAndPublishability() {
        when(objectMapper.selectById(OBJECT_ID)).thenReturn(object());
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(draftRevision(1L));
        // 冲突的一对 + 已过期的另一条（时间窗都在过去）
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 0, 0);
        List<AiMasterObjectMappingDO> entries = List.of(
                entryRow(1L, "C-1001", past, past.plusDays(1)),
                entryRow(2L, "C-1002", past, past.plusDays(1)),
                entryRow(3L, "E-9001", JAN, null, 8L));
        when(mappingMapper.selectByRevision(OBJECT_ID, 1L)).thenReturn(entries);
        when(mappingMapper.selectCurrentBySourceKey(anyLong(), any(), any())).thenReturn(List.of());

        AiMasterRevisionDetailDTO detail = service.getRevisionDetail(OBJECT_ID, 1L);

        assertThat(detail.getConflictKeys()).containsExactly("5/7/customer");
        assertThat(detail.getEntryProblems().get(1L)).isEqualTo("CONFLICT");
        assertThat(detail.getEntryProblems().get(2L)).isEqualTo("CONFLICT");
        assertThat(detail.getEntryProblems().get(3L)).isEqualTo("NONE");
        assertThat(detail.isPublishable()).isFalse();
        assertThat(detail.getEntries()).hasSize(3);

        // 已发布且无冲突的版本：不可再发布
        when(revisionMapper.selectByRevisionNo(OBJECT_ID, 1L)).thenReturn(publishedRevision(1L));
        when(mappingMapper.selectByRevision(OBJECT_ID, 1L)).thenReturn(List.of(entryRow(3L, "E-9001", JAN, null, 8L)));
        assertThat(service.getRevisionDetail(OBJECT_ID, 1L).isPublishable()).isFalse();
        assertThat(service.listEntries(OBJECT_ID, 1L)).hasSize(1);
    }

    private static void assertCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private AiMasterRevisionDraftDTO draft() {
        return new AiMasterRevisionDraftDTO().setMasterObjectId(OBJECT_ID).setValidFrom(JAN);
    }

    private AiMasterMappingEntrySaveDTO entryDTO(String sourceKey) {
        return new AiMasterMappingEntrySaveDTO()
                .setMasterObjectId(OBJECT_ID)
                .setRevisionNo(1L)
                .setApplicationId(APPLICATION_ID)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setSourceName("杭州云启科技有限公司")
                .setMatchMethod("MANUAL")
                .setValidFrom(JAN);
    }

    private static AiMasterObjectDO object() {
        return new AiMasterObjectDO()
                .setId(OBJECT_ID)
                .setObjectCode("md_customer")
                .setObjectName("企业客户")
                .setObjectType("CUSTOMER")
                .setStatus(AiMasterObjectDO.STATUS_ACTIVE)
                .setCurrentRevision(0L)
                .setVersion(3);
    }

    private static AiMasterObjectRevisionDO draftRevision(long revisionNo) {
        return new AiMasterObjectRevisionDO()
                .setId(11L)
                .setMasterObjectId(OBJECT_ID)
                .setRevisionNo(revisionNo)
                .setStatus(AiMasterObjectRevisionDO.STATUS_DRAFT)
                .setValidFrom(JAN)
                .setCreatedBy(OPERATOR)
                .setVersion(0);
    }

    private static AiMasterObjectRevisionDO publishedRevision(long revisionNo) {
        return new AiMasterObjectRevisionDO()
                .setId(11L)
                .setMasterObjectId(OBJECT_ID)
                .setRevisionNo(revisionNo)
                .setStatus(AiMasterObjectRevisionDO.STATUS_PUBLISHED)
                .setValidFrom(JAN)
                .setMappingFingerprint("frozen")
                .setEntryCount(1)
                .setCreatedBy(OPERATOR)
                .setVersion(1);
    }

    private static AiMasterObjectMappingDO entryRow(Long id, String sourceKey, LocalDateTime from, LocalDateTime to) {
        return entryRow(id, sourceKey, from, to, APPLICATION_ID);
    }

    private static AiMasterObjectMappingDO entryRow(
            Long id, String sourceKey, LocalDateTime from, LocalDateTime to, Long applicationId) {
        return entryRow(OBJECT_ID, id, sourceKey, applicationId, from, to);
    }

    private static AiMasterObjectMappingDO entryRow(
            Long masterObjectId, Long id, String sourceKey, Long applicationId, LocalDateTime from, LocalDateTime to) {
        return new AiMasterObjectMappingDO()
                .setId(id)
                .setMasterObjectId(masterObjectId)
                .setRevision(1L)
                .setApplicationId(applicationId)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setSourceName("杭州云启科技有限公司")
                .setMatchMethod("MANUAL")
                .setValidFrom(from)
                .setValidTo(to)
                .setVersion(0);
    }

    private static void loginAs(Long operatorId) {
        LoginUser loginUser = new LoginUser()
                .setId(operatorId)
                .setUserType(com.basicframework.framework.common.enums.UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }
}
