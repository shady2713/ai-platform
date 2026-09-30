package com.basicframework.module.ai.service.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectMappingDO;
import com.basicframework.module.ai.dal.dataobject.semantic.AiMasterObjectRevisionDO;
import com.basicframework.module.ai.dal.mysql.semantic.AiMasterObjectMapper;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.application.AiSystemCatalogService;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemCatalogQueryDTO;
import com.basicframework.module.ai.service.application.dto.AiSystemEntryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterCatalogEntryDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogDTO;
import com.basicframework.module.ai.service.semantic.dto.AiMasterObjectCatalogQueryDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Y02 目录发现：无权系统不出现、拒绝不可区分、预算有界，以及"发现问题照样列出"的对比。
 *
 * <p>模型可见目录里**不含源键值**是硬边界：键值是业务数据，只能留在服务端参与关联执行。
 */
class AiMasterObjectCatalogServiceImplTest {

    private static final long OBJECT_ID = 5L;

    private static final long CRM_APP = 7L;

    private static final long ERP_APP = 8L;

    private static final long REVISION_NO = 2L;

    private static final LocalDateTime JAN = LocalDateTime.of(2026, 1, 1, 0, 0);

    private static final LocalDateTime JUN = LocalDateTime.of(2026, 6, 1, 0, 0);

    private AiMasterObjectMapper objectMapper;

    private AiMasterRevisionVerifier verifier;

    private AiSystemCatalogService systemCatalogService;

    private AiMasterObjectCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = mock(AiMasterObjectMapper.class);
        verifier = mock(AiMasterRevisionVerifier.class);
        systemCatalogService = mock(AiSystemCatalogService.class);
        service = new AiMasterObjectCatalogServiceImpl(objectMapper, verifier, systemCatalogService);
        when(objectMapper.selectByCode("md_customer")).thenReturn(object("ACTIVE"));
    }

    @Test
    void rejectsIncompleteRequestsAndUnavailableObjects() {
        assertCode(() -> service.discover(null), AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(() -> service.discover(query().setAsOf(null)), AiErrorCodeConstants.AI_REQUEST_INVALID);
        assertCode(() -> service.discover(query().setSubjectType(" ")), AiErrorCodeConstants.AI_REQUEST_INVALID);

        when(objectMapper.selectByCode("md_customer")).thenReturn(null);
        assertCode(() -> service.discover(query()), AiErrorCodeConstants.AI_MASTER_OBJECT_NOT_EXISTS);

        when(objectMapper.selectByCode("md_customer")).thenReturn(object("DISABLED"));
        assertCode(() -> service.discover(query()), AiErrorCodeConstants.AI_MASTER_OBJECT_DISABLED_CONFLICT);
    }

    @Test
    void deniedSubjectGetsEmptyCatalogIndistinguishableFromUnregistered() {
        verified(List.of(entry("C-1001", CRM_APP, JAN, null)));
        // 主体未登记 / 已停用 / 无任何可访问系统：Y01 目录一律 denied，本服务照同样形状回答
        when(systemCatalogService.discover(any(AiSystemCatalogQueryDTO.class))).thenReturn(deniedCatalog());

        AiMasterObjectCatalogDTO first = service.discover(query());
        AiMasterObjectCatalogDTO second = service.discover(query().setExternalUserId("someone-else"));

        assertThat(first.isDenied()).isTrue();
        assertThat(first.getEntries()).isEmpty();
        assertThat(first.getModelCatalog()).isEqualTo("[]");
        assertThat(first.getCatalogFingerprint()).isNotBlank();
        // 指纹只由身份与对象版本决定，条目事实不参与：两个不同主体的拒绝目录形状一致，不泄漏登记状态
        assertThat(second.getCatalogFingerprint()).isNotEqualTo(first.getCatalogFingerprint());
        assertThat(second.isDenied()).isTrue();
        assertThat(second.getModelCatalog()).isEqualTo("[]");
    }

    @Test
    void entriesFromSystemsWithoutAccessNeverAppear() {
        verified(List.of(entry("C-1001", CRM_APP, JAN, null), entry("E-9001", ERP_APP, JAN, null)));
        when(systemCatalogService.discover(any(AiSystemCatalogQueryDTO.class))).thenReturn(accessibleCatalog(CRM_APP));

        AiMasterObjectCatalogDTO catalog = service.discover(query());

        assertThat(catalog.isDenied()).isFalse();
        assertThat(catalog.getEntries())
                .extracting(AiMasterCatalogEntryDTO::getSourceKey)
                .containsExactly("C-1001");
        assertThat(catalog.getModelCatalog()).contains("crm-app").doesNotContain("ERP");
        // 模型可见目录不含源键值（业务数据不进入提示词）
        assertThat(catalog.getModelCatalog()).doesNotContain("C-1001");
        assertThat(catalog.getEntries().get(0).isUsable()).isTrue();
        assertThat(catalog.getEntries().get(0).getProblem()).isEqualTo("NONE");
    }

    @Test
    void problemsAreFlaggedInsteadOfDropped() {
        // CRM 侧两条重叠（一对多冲突），ERP 侧一条已过期
        verified(List.of(
                entry("C-1001", CRM_APP, JAN, null),
                entry("C-1002", CRM_APP, JAN, null),
                entry("E-9001", ERP_APP, JAN, JUN)));
        when(systemCatalogService.discover(any(AiSystemCatalogQueryDTO.class)))
                .thenReturn(accessibleCatalog(CRM_APP, ERP_APP));

        AiMasterObjectCatalogDTO catalog = service.discover(query());

        assertThat(catalog.getEntries()).hasSize(3);
        assertThat(catalog.getEntries())
                .filteredOn(entry -> "C-1001".equals(entry.getSourceKey()))
                .singleElement()
                .extracting(AiMasterCatalogEntryDTO::getProblem)
                .isEqualTo("CONFLICT");
        assertThat(catalog.getEntries())
                .filteredOn(entry -> "C-1002".equals(entry.getSourceKey()))
                .singleElement()
                .extracting(AiMasterCatalogEntryDTO::isUsable)
                .isEqualTo(false);
        assertThat(catalog.getEntries())
                .filteredOn(entry -> "E-9001".equals(entry.getSourceKey()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getProblem()).isEqualTo("EXPIRED");
                    assertThat(entry.isInForce()).isFalse();
                });
        assertThat(catalog.getModelCatalog()).contains("\"usable\":false");
    }

    @Test
    void visibleEntriesOverBudgetAreRejectedInsteadOfTruncated() {
        List<AiMasterObjectMappingDO> many = new ArrayList<>();
        for (int index = 0; index < AiMasterObjectCatalogServiceImpl.MAX_VISIBLE_ENTRIES + 1; index++) {
            many.add(entry("C-" + index, CRM_APP, JAN, null));
        }
        verified(many);
        assertCode(() -> service.discover(query()), AiErrorCodeConstants.AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED);
    }

    @Test
    void catalogFingerprintChangesWithVisibleFacts() {
        verified(List.of(entry("C-1001", CRM_APP, JAN, null)));
        when(systemCatalogService.discover(any(AiSystemCatalogQueryDTO.class))).thenReturn(accessibleCatalog(CRM_APP));
        String baseline = service.discover(query()).getCatalogFingerprint();

        // 同一份事实重复读取：指纹稳定
        assertThat(service.discover(query()).getCatalogFingerprint()).isEqualTo(baseline);

        // 可见条目变化（换版本内容）：指纹变化
        verified(List.of(entry("C-1002", CRM_APP, JAN, null)));
        assertThat(service.discover(query()).getCatalogFingerprint()).isNotEqualTo(baseline);
    }

    private void verified(List<AiMasterObjectMappingDO> entries) {
        AiMasterObjectRevisionDO revision = new AiMasterObjectRevisionDO()
                .setId(11L)
                .setMasterObjectId(OBJECT_ID)
                .setRevisionNo(REVISION_NO)
                .setStatus(AiMasterObjectRevisionDO.STATUS_PUBLISHED)
                .setValidFrom(JAN)
                .setMappingFingerprint("frozen-fingerprint")
                .setEntryCount(entries.size())
                .setVersion(1);
        when(verifier.verify(eq(OBJECT_ID), eq(REVISION_NO), any()))
                .thenReturn(new AiMasterRevisionVerifier.VerifiedRevision(
                        revision,
                        entries.stream().map(AiMasterObjectServiceImpl::toLine).toList()));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable)
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private static AiMasterObjectCatalogQueryDTO query() {
        return new AiMasterObjectCatalogQueryDTO()
                .setObjectCode("md_customer")
                .setRevisionNo(REVISION_NO)
                .setApplicationId(CRM_APP)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setAsOf(JUN);
    }

    private static AiSystemCatalogDTO deniedCatalog() {
        return new AiSystemCatalogDTO()
                .setApplicationId(CRM_APP)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setDenied(true)
                .setEntries(List.of())
                .setCatalogFingerprint("denied-fingerprint")
                .setModelCatalog("[]");
    }

    private static AiSystemCatalogDTO accessibleCatalog(Long... applicationIds) {
        List<AiSystemEntryDTO> entries = new ArrayList<>();
        for (Long applicationId : applicationIds) {
            entries.add(new AiSystemEntryDTO()
                    .setApplicationId(applicationId)
                    .setAppCode(applicationId.equals(CRM_APP) ? "crm-app" : "erp-app")
                    .setSystemName(applicationId.equals(CRM_APP) ? "CRM 系统" : "ERP 系统")
                    .setCurrentSystem(applicationId.equals(CRM_APP))
                    .setSubjectType("USER")
                    .setExternalUserId("alice")
                    .setScopeSource("it-scope")
                    .setScopeVersion(1L)
                    .setScopes(List.of())
                    .setSystemFingerprint("system-" + applicationId));
        }
        return new AiSystemCatalogDTO()
                .setApplicationId(CRM_APP)
                .setSubjectType("USER")
                .setExternalUserId("alice")
                .setDenied(false)
                .setEntries(entries)
                .setCatalogFingerprint("catalog-fingerprint")
                .setModelCatalog("[]");
    }

    private static AiMasterObjectDO object(String status) {
        return new AiMasterObjectDO()
                .setId(OBJECT_ID)
                .setObjectCode("md_customer")
                .setObjectName("企业客户")
                .setObjectType("CUSTOMER")
                .setStatus(status)
                .setCurrentRevision(REVISION_NO)
                .setVersion(3);
    }

    private static AiMasterObjectMappingDO entry(
            String sourceKey, Long applicationId, LocalDateTime from, LocalDateTime to) {
        return new AiMasterObjectMappingDO()
                .setId(1L)
                .setMasterObjectId(OBJECT_ID)
                .setRevision(REVISION_NO)
                .setApplicationId(applicationId)
                .setEntityType("customer")
                .setSourceKey(sourceKey)
                .setSourceName("杭州云启科技有限公司")
                .setMatchMethod("MANUAL")
                .setValidFrom(from)
                .setValidTo(to)
                .setVersion(0);
    }
}
